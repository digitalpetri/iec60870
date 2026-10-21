package com.digitalpetri.iec60870.asdu.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.digitalpetri.iec60870.asdu.object.SinglePointInformation;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class TimeTaggedTest {

  @Test
  void everyTimedRecordExposesItsTimeTag() throws Exception {
    int cp56 = 0;
    int cp24 = 0;
    for (Class<?> type : objectRecords()) {
      for (RecordComponent component : type.getRecordComponents()) {
        if (!component.getName().equals("time")) {
          continue;
        }
        if (component.getType() == Cp56Time2a.class) {
          assertTrue(Cp56TimeTagged.class.isAssignableFrom(type), type.getName());
          cp56++;
        } else if (component.getType() == Cp24Time2a.class) {
          assertTrue(Cp24TimeTagged.class.isAssignableFrom(type), type.getName());
          cp24++;
        }
      }
    }
    assertEquals(20, cp56);
    assertEquals(11, cp24);
  }

  @Test
  void cp16ConvertsToDuration() {
    assertEquals(Duration.ofMillis(59_999), new Cp16Time2a(59_999).toDuration());
    assertEquals(Duration.ZERO, new Cp16Time2a(0).toDuration());
  }

  private static List<Class<?>> objectRecords()
      throws IOException, URISyntaxException, ClassNotFoundException {
    Path dir =
        Path.of(SinglePointInformation.class.getResource("SinglePointInformation.class").toURI())
            .getParent();
    String pkg = SinglePointInformation.class.getPackageName();
    List<String> names;
    try (Stream<Path> files = Files.list(dir)) {
      names =
          files
              .map(p -> p.getFileName().toString())
              .filter(n -> n.endsWith(".class") && !n.contains("$"))
              .map(n -> pkg + "." + n.substring(0, n.length() - ".class".length()))
              .toList();
    }
    List<Class<?>> records = new ArrayList<>();
    for (String name : names) {
      Class<?> type = Class.forName(name);
      if (type.isRecord()) {
        records.add(type);
      }
    }
    return records;
  }
}
