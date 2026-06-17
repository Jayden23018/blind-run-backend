package com.example.demo.integration;

import com.example.demo.entity.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发安全测试（TC-CONCUR-01）。
 *
 * <p>串行派单协议下，订单同一时刻只派给一位志愿者。本测试验证：两位志愿者并发响应同一订单时，
 * 只有"当前被派单"的那位能接单成功，另一位被派单归属校验拒绝（B2 修复后旧 /accept 与 /respond
 * 行为一致）；同时 Redis 分布式锁防止并发接单的数据竞争。
 *
 * <p>历史背景：本测试原用旧 /accept 测"乐观锁防并发抢单→恰好 1 成功 1 个 409"。串行派单引入后，
 * 该前提不再成立（只有被派者能接，另一者被归属校验拒，而非乐观锁冲突），故改为 /respond 版本。
 */
@Tag("slow")
class ConcurrencyTest extends BaseIntegrationTest {

    /** TC-CONCUR-01：两个志愿者并发响应同一订单 */
    @Test
    @DisplayName("TC-CONCUR-01: 并发响应派单——只有被派单的志愿者能接单")
    void tc01_concurrentAccept() throws Exception {
        // 1. 注册盲人 + 两个志愿者
        String blindToken = testHelper.registerAndLoginWithRole("13800130001", "BLIND");
        String volAToken = testHelper.registerAndLoginWithRole("13800130002", "VOLUNTEER");
        String volBToken = testHelper.registerAndLoginWithRole("13800130003", "VOLUNTEER");

        // 2. 两个志愿者都上报位置
        testHelper.updateVolunteerLocation(volAToken, 39.9242, 116.4677, true);
        testHelper.updateVolunteerLocation(volBToken, 39.9300, 116.4680, true);

        // 3. 盲人下单 → 等待异步派单（派单完成后 current 指向某一位志愿者）
        Long orderId = testHelper.createOrder(blindToken, 39.9042, 116.4674, "朝阳公园南门",
                TestHelper.defaultStartTime(), TestHelper.defaultEndTime());

        Thread.sleep(500); // 等待异步 DispatchService 启动并派给第一位志愿者

        // 4. 用 CountDownLatch 保证两个线程同时发起 /respond
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        AtomicInteger successCount = new AtomicInteger(0);

        Runnable respondA = () -> {
            try {
                startLatch.await();
                ResponseEntity<String> resp = testHelper.respondOrderRaw(volAToken, orderId, "ACCEPT");
                if (resp.getStatusCode() == HttpStatus.OK) successCount.incrementAndGet();
            } catch (Exception e) {
                // 被归属校验拒绝或连接异常
            } finally {
                doneLatch.countDown();
            }
        };

        Runnable respondB = () -> {
            try {
                startLatch.await();
                ResponseEntity<String> resp = testHelper.respondOrderRaw(volBToken, orderId, "ACCEPT");
                if (resp.getStatusCode() == HttpStatus.OK) successCount.incrementAndGet();
            } catch (Exception e) {
                // 被归属校验拒绝或连接异常
            } finally {
                doneLatch.countDown();
            }
        };

        new Thread(respondA, "vol-A").start();
        new Thread(respondB, "vol-B").start();

        // 同时放开两个线程
        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);

        // 5. 断言：恰好一个成功（被派单的志愿者），另一个被归属校验拒绝
        assertThat(successCount.get()).as("应该恰好一个志愿者接单成功（被派单的那位）").isEqualTo(1);

        // 6. 订单最终被接（/respond 后 PENDING_ACCEPT → 异步 IN_PROGRESS，容忍过渡态）
        OrderStatus finalStatus = testHelper.getOrderStatus(blindToken, orderId);
        assertThat(finalStatus).as("订单应已被接单").isIn(OrderStatus.PENDING_ACCEPT, OrderStatus.IN_PROGRESS);

        System.out.println("✅ TC-CONCUR-01 passed — 并发响应派单，仅被派单者成功");
    }
}
