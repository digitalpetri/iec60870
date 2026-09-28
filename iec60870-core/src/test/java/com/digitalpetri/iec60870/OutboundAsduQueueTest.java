package com.digitalpetri.iec60870;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.address.CommonAddress;
import com.digitalpetri.iec60870.address.InformationObjectAddress;
import com.digitalpetri.iec60870.address.OriginatorAddress;
import com.digitalpetri.iec60870.asdu.Asdu;
import com.digitalpetri.iec60870.asdu.AsduType;
import com.digitalpetri.iec60870.asdu.Cause;
import com.digitalpetri.iec60870.asdu.object.ReadCommand;
import com.digitalpetri.iec60870.session.OutboundAsduQueue;
import java.util.List;
import org.junit.jupiter.api.Test;

class OutboundAsduQueueTest {
  @Test
  void protectedEntriesEvictOnlyTheOldestDiscardableEntry() {
    for (OutboundQueuePolicy policy : OutboundQueuePolicy.values()) {
      var queue = new OutboundAsduQueue(3, policy);
      var protectedEntry = queue.offer(asdu(1), true);
      var oldestEvent = queue.offer(asdu(2), false);
      var newestEvent = queue.offer(asdu(3), false);
      var response = queue.offer(asdu(4), true);
      assertTrue(oldestEvent.completion().toCompletableFuture().isCompletedExceptionally());
      assertFalse(response.completion().toCompletableFuture().isDone());
      assertSame(protectedEntry, queue.removeFirst());
      assertSame(newestEvent, queue.removeFirst());
      assertSame(response, queue.removeFirst());
    }
  }

  @Test
  void protectedEntriesCannotEvictOneAnother() {
    var queue = new OutboundAsduQueue(1, OutboundQueuePolicy.DROP_OLDEST);
    var first = queue.offer(asdu(1), true);
    var rejected = queue.offer(asdu(2), true);
    assertTrue(rejected.completion().toCompletableFuture().isCompletedExceptionally());
    assertFalse(first.completion().toCompletableFuture().isDone());
    assertSame(first, queue.removeFirst());
  }

  private static Asdu asdu(int address) {
    return new Asdu(
        AsduType.C_RD_NA_1,
        false,
        Cause.REQUEST,
        false,
        false,
        OriginatorAddress.none(),
        CommonAddress.of(1),
        List.of(new ReadCommand(InformationObjectAddress.of(address))));
  }
}
