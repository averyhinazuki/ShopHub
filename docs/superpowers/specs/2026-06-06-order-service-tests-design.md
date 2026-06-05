# OrderServiceTest Design

**Date:** 2026-06-06
**Scope:** Unit tests for `OrderService` checkout hot path and pay flow

---

## Approach

Mockito unit tests (`@ExtendWith(MockitoExtension.class)`), matching the existing `CartServiceTest` / `ProductServiceTest` pattern.

`OrderService` uses self-injection (`@Lazy @Autowired OrderService self`) so internal `@Transactional` calls go through the AOP proxy. In tests, `self` is injected as a `@Mock` via `ReflectionTestUtils.setField` in `@BeforeEach`, allowing `loadCartSnapshot()` and `persistOrder()` to be stubbed independently when testing `checkout()`.

---

## Setup

```
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    // Repos & infra
    @Mock OrderRepository            orderRepository;
    @Mock OrderItemRepository        orderItemRepository;
    @Mock CartRepository             cartRepository;
    @Mock CartItemRepository         cartItemRepository;
    @Mock ProductInventoryRepository inventoryRepository;
    @Mock ProductRepository          productRepository;
    @Mock ApplicationEventPublisher  eventPublisher;
    @Mock RedissonClient             redissonClient;
    @Mock ProductCacheService        cacheService;
    @Mock SecurityUtils              securityUtils;

    // Self-injection proxy + Redisson lock
    @Mock OrderService self;
    @Mock RLock        lock;

    @InjectMocks OrderService orderService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(orderService, "self", self);
        when(securityUtils.resolveUserId()).thenReturn(1L);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
    }
}
```

---

## Test Cases

### `loadCartSnapshot()` — 3 tests

| # | Name | Setup | Assert |
|---|------|-------|--------|
| 1 | `loadCartSnapshot_emptyCart_throwsIllegalArgument` | cart exists, `cartItemRepository.findByCartId` returns empty list | throws `IllegalArgumentException` |
| 2 | `loadCartSnapshot_inactiveProduct_throwsIllegalArgument` | cart has one item, product status = `INACTIVE` | throws `IllegalArgumentException` |
| 3 | `loadCartSnapshot_success_returnsSnapshot` | cart has two ACTIVE items | returned snapshot has correct productId, qty, price for each item |

### `checkout()` — 4 tests

`self.loadCartSnapshot()` is stubbed to return a pre-built snapshot. `self.persistOrder()` is stubbed for the success case.

| # | Name | Setup | Assert |
|---|------|-------|--------|
| 4 | `checkout_secondItemSoldOut_throwsSoldOutException_andCompensatesFirst` | snapshot has 2 items; `deductStock` returns 1 for item 1, 0 for item 2 | throws `SoldOutException`; `restoreStock` called once for item 1; `cacheService.deleteCache` called for item 1 in compensation |
| 5 | `checkout_lockTimeout_throwsRuntimeException_noCompensation` | `lock.tryLock` returns `false` on first item | throws `RuntimeException`; `restoreStock` never called |
| 6 | `checkout_persistOrderFails_compensatesAllDeductions` | snapshot has 2 items; both deductions succeed; `self.persistOrder` throws `RuntimeException` | `restoreStock` called twice (once per item) |
| 7 | `checkout_success_returnsOrderResponse` | snapshot has 1 item; deduction succeeds; `self.persistOrder` returns an `OrderResponse` | returns the `OrderResponse`; no compensation calls |

### `pay()` — 4 tests

| # | Name | Setup | Assert |
|---|------|-------|--------|
| 8 | `pay_success_returnsOrderResponse` | order exists, belongs to user, `payIfPending` returns 1 | returns `OrderResponse`; `eventPublisher.publishEvent` called once |
| 9 | `pay_orderNotFound_throwsResourceNotFound` | `orderRepository.findById` returns empty | throws `ResourceNotFoundException` |
| 10 | `pay_alreadyPaidOrCancelled_throwsIllegalState` | order exists, `payIfPending` returns 0 | throws `IllegalStateException` |
| 11 | `pay_otherUsersOrder_throwsResourceNotFound` | order exists but `userId` differs from caller | throws `ResourceNotFoundException` (not 403) |

---

## File Location

`src/test/java/com/example/flashsale/service/OrderServiceTest.java`

---

## Out of Scope

- `getMyOrders`, `getAllOrders`, `getOrder` — straightforward delegation to repo, low risk
- Integration test for the happy path — follow-up task
