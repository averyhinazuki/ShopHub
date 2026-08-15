# 13 — Terraform and AWS

Unit 13. Prerequisite: `11` (immutable infrastructure, `user_data.sh.tpl`, the `/data` volume).

⚠️ **The app stack is currently destroyed** — see "Evidence from your own state" below.

---

# Concept 1 — Declarative infrastructure and state ✅

## What Terraform does

You declare **what should exist**, not how to create it. Terraform reads three things — your config
(desired), its **state** (what it believes exists), and the AWS API (what actually exists) — then
computes the difference. `plan` shows it, `apply` executes it.

Apply twice with no changes and the second is a no-op: desired already equals actual. That
**idempotence** is what separates this from a shell script.

## State is the memory

`terraform.tfstate` maps *your names* to *real cloud IDs*: `aws_instance.shophub` →
`i-04ce9c7f5473e048c`. Without it, Terraform couldn't know last week's instance is the one your
config describes, and would create a second.

**State is sensitive.** It stores resource attributes *including rendered `user_data`*, which for
this stack embeds `mysql_root_password`, `jwt_secret` and `grafana_admin_password` in plaintext.
Hence `.gitignore:75-85`. (An empty state is committed at the repo root — harmless, but → **F20**.)

## Drift

Reality changing outside Terraform. Real case here: an `amazon-ssm-agent` hand-installed on the box
to unblock CI. Terraform didn't know, so the next `plan` was computed against a false picture — and
the change would have vanished on replacement. Fixed by declaring it in `user_data.sh.tpl:17-18`.

> The declarative model only works if **everything** goes through it. Every manual fix is drift, and
> drift is a lie your next `plan` believes.

---

# Concept 2 — `resource` vs `data` *is* the safety mechanism ✅

| | `resource` | `data` |
|---|---|---|
| Terraform | **owns** it | only **reads** it |
| Create / modify | ✅ | ❌ |
| **Destroy** | ✅ | **❌ never** |

```hcl
data "aws_ebs_volume" "data" {                # main.tf:38 — READ ONLY
  filter { name = "tag:Name"  values = ["shophub-data"] }
}

resource "aws_volume_attachment" "data" {     # main.tf:116 — owns the ATTACHMENT
  volume_id   = data.aws_ebs_volume.data.id
  instance_id = aws_instance.shophub.id
}
```

**The app stack owns the attachment, never the volume.** `terraform destroy` detaches the disk; it
has no mechanism to delete it. Same shape for the address — `data "aws_eip"` (`:130`) plus
`resource "aws_eip_association"` (`:137`): destroy disassociates, the address stays allocated.

Not a convention or a policy — **structural**. The capability doesn't exist.

Also `force_detach = true` (`:124`): instance replacement detaches cleanly, but a targeted destroy
against a live box would hang without it. ext4 journals, so worst case is a recovery on next mount.

---

# Concept 3 — Two roots, two different threats ✅

`persistent/main.tf` is a separate configuration with its own state. The app stack is disposable;
the data must not be. **Both** protections are used, for **different** threats:

| Threat | Protection | Where |
|---|---|---|
| `destroy` in the **app stack** | **State separation** — the volume isn't in that state at all | by construction |
| `destroy` in the **persistent stack** | **`prevent_destroy`** — Terraform refuses | `persistent/main.tf:86-88`, `:107-109` |

The comment at `:83-85` states it: `prevent_destroy` *"guards against a `terraform destroy` run from
inside this directory, which the state separation alone doesn't prevent."* Defence in depth.

**Why `prevent_destroy` alone would be wrong:** it makes `terraform destroy` **fail outright**
rather than skip the resource. You could never tear down the app stack without editing code first.
State separation makes teardown routine *and* keeps the data unreachable.

## Evidence from your own state files

| State | Serial | Resources | Modified |
|---|---|---|---|
| `infra/terraform/terraform.tfstate` | 263 | **0** | 2026-07-26 **01:28** |
| `infra/terraform/terraform.tfstate.backup` | 242 | **20** | 2026-07-26 **01:27** |
| `infra/terraform/persistent/terraform.tfstate` | 3 | **5** | 2026-07-22 |

One minute apart, the app stack went 20 → 0. That's a `terraform destroy` — instance, security
group, IAM role and profile, key pair, attachment, EIP association. **The persistent stack was
untouched.** The volume and address survived, in a state the destroy could not reach.

The design proved itself on live infrastructure. Current spend: EIP ~$3.60/mo + 10 GiB volume
~$0.80/mo ≈ **$4.40/month**, versus ~$69 running.

---

# Concept 4 — Why the subnet is derived from the volume ✅

**An EBS volume is pinned to one Availability Zone** and cannot attach across zones. So the instance
*must* land in the volume's AZ. Two ways: write the AZ in both places and hope they stay in sync, or
**derive** one from the other.

```hcl
# persistent/main.tf:64-70 — the stateful layer PICKS the AZ
data "aws_subnet" "chosen" { id = sort(data.aws_subnets.default.ids)[0] }
resource "aws_ebs_volume" "data" { availability_zone = data.aws_subnet.chosen.availability_zone }
```
```hcl
# main.tf:17-27 — the app stack FOLLOWS it
data "aws_subnets" "default" {
  filter { name = "vpc-id"            values = [data.aws_vpc.default.id] }
  filter { name = "availability-zone" values = [data.aws_ebs_volume.data.availability_zone] }
}
```

`local.subnet_id` can only ever be in the right zone.

> A constraint enforced by **derivation** cannot drift. A constraint enforced by **agreement between
> two values** eventually will.

Note the direction: the **stateful** layer chooses, the disposable layer follows. The thing that's
expensive to move decides.

---

# Concept 5 — What forces a replacement ✅

Terraform updates in place where it can; some changes destroy and recreate.

**`user_data_replace_on_change = true`** (`:105`) — cloud-init runs user-data only on **first boot**,
so editing the script has no effect on a running instance. Without this flag you'd change code,
apply, see "no changes," and the box would keep running the old bootstrap. Safe *"because the
databases live on the EBS volume attached below, outside this state"* — Concepts 2 and 3 are what
make replacement cheap.

## The AMI war story

`data "aws_ssm_parameter" "al2023"` replaced a **name-glob** data source, because
`al2023-ami-*-x86_64` also matched `al2023-ami-**minimal**-*`. The minimal image published minutes
later, `most_recent = true` picked it, and the minimal AMI **lacks the SSM agent** — breaking every
CI deploy with `InvalidInstanceId`.

> `most_recent = true` over a glob means **AWS's publishing schedule decides your infrastructure**.
> That is not a pin; it is a subscription.

## Gotcha — the residual risk

The SSM parameter still **re-resolves on every plan**, and `ami` forces replacement. So **any future
`apply`, for any unrelated reason, can silently replace the instance** because Amazon shipped a new
image — an unplanned OS upgrade and ~2 minutes of downtime while you were fixing something else.

Acknowledged in the code and genuinely cheap, but cheap is not the same as intended.
`lifecycle { ignore_changes = [ami] }` makes upgrades deliberate. (→ **F21**)

## Questions I should be able to answer

- What three inputs does Terraform compare, and what makes `apply` idempotent?
- What does the state file map, and why is it the most sensitive file in the repo?
- What is drift, and what two separate harms does a manual fix cause?
- State the difference between `resource` and `data` in one sentence about capability.
- What exactly does `terraform destroy` do to the EBS volume, and why can it do no more?
- Why isn't `prevent_destroy` alone sufficient — what would it break?
- Which layer picks the AZ and which follows? Why that direction?
- Why does editing `user_data.sh.tpl` need `user_data_replace_on_change`?
- What went wrong with the AMI name-glob, and why is an SSM parameter safer?
- Why can an unrelated `terraform apply` replace your instance today?
