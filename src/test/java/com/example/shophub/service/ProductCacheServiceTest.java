package com.example.shophub.service;

import com.example.shophub.dto.product.ProductResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * F12. Cache-aside's defining promise is that the cache is not in the data path:
 * MySQL is the truth, and Redis may be wiped or unreachable with no loss of
 * correctness. These pin that promise — a Redis outage must degrade product
 * browsing to "slower", never to "down".
 */
@ExtendWith(MockitoExtension.class)
class ProductCacheServiceTest {

    @Mock StringRedisTemplate stringRedisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    private ProductCacheService cacheService;

    @BeforeEach
    void setUp() {
        cacheService = new ProductCacheService(stringRedisTemplate, new ObjectMapper());
    }

    @Test
    void getDetail_redisUnreachable_returnsNullSoTheCallerFallsThroughToMySql() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenThrow(new RedisConnectionFailureException("Redis unreachable"));

        assertThat(cacheService.getDetail(1L)).isNull();
    }

    @Test
    void setDetail_redisUnreachable_doesNotPropagate() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        doThrow(new RedisConnectionFailureException("Redis unreachable"))
                .when(valueOps).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        ProductResponse response = new ProductResponse();
        response.setId(1L);

        assertThatNoException().isThrownBy(() -> cacheService.setDetail(1L, response));
    }

    @Test
    void deleteCache_redisUnreachable_doesNotPropagate() {
        when(stringRedisTemplate.delete(anyString()))
                .thenThrow(new RedisConnectionFailureException("Redis unreachable"));

        assertThatNoException().isThrownBy(() -> cacheService.deleteCache(1L));
    }

    /** A cache hit must still work normally — degradation must not become "never cache". */
    @Test
    void getDetail_cacheHit_deserializesNormally() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("product:1:detail"))
                .thenReturn("{\"id\":1,\"name\":\"Widget\",\"availableStock\":7}");

        ProductResponse result = cacheService.getDetail(1L);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("Widget");
        assertThat(result.getAvailableStock()).isEqualTo(7);
    }
}
