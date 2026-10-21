package com.digitalpetri.iec60870.transport.tcp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.digitalpetri.iec60870.AsduDecodeException;
import com.digitalpetri.iec60870.transport.TransportListener;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Tests that octets the 104 frame decoder rejects reach the listener before the loss. */
class FramingErrorReportTest {

  @Test
  void badStartOctetIsReportedWithItsOctetsBeforeTheLoss() {
    List<String> calls = new ArrayList<>();
    List<byte[]> octets = new ArrayList<>();
    List<Boolean> readOnly = new ArrayList<>();
    TransportListener listener =
        new TransportListener() {
          @Override
          public void onFrame(ByteBuf frame) {
            calls.add("frame");
          }

          @Override
          public void onFramingError(ByteBuf bytes, RuntimeException cause) {
            assertInstanceOf(AsduDecodeException.class, cause);
            readOnly.add(bytes.isReadOnly());
            octets.add(ByteBufUtil.getBytes(bytes));
            calls.add("framingError");
          }

          @Override
          public void onConnectionLost(@Nullable Throwable cause) {
            calls.add("lost");
          }
        };
    EmbeddedChannel channel =
        new EmbeddedChannel(new Iec104FrameDecoder(), new InboundFrameHandler(() -> listener));

    // One good STARTDT con, then garbage.
    channel.writeInbound(
        Unpooled.wrappedBuffer(HexFormat.of().parseHex("68040b000000" + "0004070000")));

    assertEquals(List.of("frame", "framingError", "lost"), calls);
    assertArrayEquals(HexFormat.of().parseHex("0004070000"), octets.get(0));
    assertEquals(List.of(true), readOnly);
    assertFalse(channel.isOpen(), "the transport closes the connection");
    channel.finishAndReleaseAll();
  }
}
