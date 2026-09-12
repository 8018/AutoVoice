package com.autovoice.server.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** D10b 下行字节预算:慢客户端不得无界堆积下行数据。 */
class DownlinkBudgetTest {

    @Test
    void withinBudgetIsAccepted() {
        DownlinkBudget budget = new DownlinkBudget(1_000);
        assertTrue(budget.tryReserve(600));
        assertTrue(budget.tryReserve(400));
        assertEquals(0, budget.remaining());
    }

    @Test
    void overBudgetIsRejected() {
        DownlinkBudget budget = new DownlinkBudget(1_000);
        assertTrue(budget.tryReserve(800));
        assertFalse(budget.tryReserve(300), "超出预算必须拒绝(明确溢出,不静默丢弃)");
        assertEquals(200, budget.remaining(), "拒绝的预留不得改变已用配额");
    }

    @Test
    void releaseRestoresCapacityAfterSend() {
        DownlinkBudget budget = new DownlinkBudget(1_000);
        assertTrue(budget.tryReserve(1_000));
        assertFalse(budget.tryReserve(1));
        budget.release(1_000); // 发送完成/失败后归还
        assertTrue(budget.tryReserve(1_000), "归还后可再次预留");
    }

    @Test
    void singleMessageLargerThanBudgetIsRejected() {
        DownlinkBudget budget = new DownlinkBudget(500);
        assertFalse(budget.tryReserve(501), "单条超预算消息必须拒绝,不得突破上界");
    }

    @Test
    void releaseIsIdempotentAndNeverGoesNegative() {
        DownlinkBudget budget = new DownlinkBudget(100);
        budget.release(100); // 未预留即归还
        assertEquals(100, budget.remaining());
        assertTrue(budget.tryReserve(100));
        budget.release(100);
        budget.release(100);
        assertEquals(100, budget.remaining());
    }
}
