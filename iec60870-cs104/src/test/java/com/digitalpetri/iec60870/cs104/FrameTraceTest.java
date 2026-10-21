package com.digitalpetri.iec60870.cs104;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.diagnostics.Direction;
import com.digitalpetri.iec60870.test.common.CapturingLogger;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

class FrameTraceTest {

  @Test
  void formatsDirectionControlFieldAndOctets() {
    assertEquals(
        "OUT I(N(S)=0 N(R)=0) 68 0e 00 00 00 00 64 01 06 00 01 00 00 00 00 14",
        FrameTrace.format(Direction.OUT, frame("680e00000000640106000100000000" + "14")));
    assertEquals(
        "IN S(N(R)=3) 68 04 01 00 06 00", FrameTrace.format(Direction.IN, frame("680401000600")));
    assertEquals(
        "IN U(TESTFR_CON) 68 04 83 00 00 00",
        FrameTrace.format(Direction.IN, frame("680483000000")));
  }

  @Test
  void undecodableControlFieldIsShownAsQuestionMark() {
    assertEquals("IN ? 68 04 0f 00 00 00", FrameTrace.format(Direction.IN, frame("68040f000000")));
    assertEquals("IN ? 68 02", FrameTrace.format(Direction.IN, frame("6802")));
  }

  @Test
  void formatDoesNotMoveTheIndexes() {
    ByteBuf buffer = frame("680483000000");
    FrameTrace.format(Direction.IN, buffer);
    assertEquals(0, buffer.readerIndex());
  }

  @Test
  void loggerTraceFollowsTheLoggerLevel() {
    var logger = new CapturingLogger("trace", Level.DEBUG);
    ApciDiagnostics trace = FrameTrace.logger(logger);
    assertFalse(trace.traceFrames());

    logger.setThreshold(Level.TRACE);
    assertTrue(trace.traceFrames());
    trace.onFrame(Direction.OUT, frame("680443000000"));

    assertEquals(1, logger.entries().size());
    assertEquals(Level.TRACE, logger.entries().get(0).level());
    assertEquals(
        List.of("OUT U(TESTFR_ACT) 68 04 43 00 00 00"), List.of(logger.entries().get(0).message()));
  }

  private static ByteBuf frame(String hex) {
    return Unpooled.wrappedBuffer(HexFormat.of().parseHex(hex));
  }
}
