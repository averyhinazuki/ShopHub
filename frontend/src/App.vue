<template>
  <div class="min-h-screen bg-white text-gray-900" style="font-family: system-ui, -apple-system, sans-serif">
    <NavBar />
    <main class="max-w-5xl mx-auto px-4 py-10">
      <RouterView />
    </main>
  </div>
</template>

<script setup>
import NavBar from './components/NavBar.vue'
</script>
