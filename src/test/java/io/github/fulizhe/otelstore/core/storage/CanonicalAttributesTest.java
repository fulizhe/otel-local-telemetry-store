package io.github.fulizhe.otelstore.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fulizhe.otelstore.core.model.KeyValue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 规范化哈希的两条不变量：<b>与顺序无关</b>、<b>与类型有关</b>。
 *
 * <p>这两条任意一条破了，后果都是"字典表静默失效"—— 不报错、不崩，只是每条记录
 * 都多出一个外键，而 {@code resource_dict} 悄悄长到和记录数一样大。
 * 所以它们必须各有一个测试，而不是靠"读代码觉得对"。
 */
class CanonicalAttributesTest {

    @Test
    @DisplayName("键顺序不同但内容相同 → 规范化文本与哈希都相同")
    void orderDoesNotMatter() {
        final List<KeyValue> a = new ArrayList<KeyValue>(Arrays.asList(
                KeyValue.of("service.name", "checkout"),
                KeyValue.of("host.name", "node-1"),
                KeyValue.of("host.arch", "amd64")));
        final List<KeyValue> b = new ArrayList<KeyValue>(Arrays.asList(
                KeyValue.of("host.arch", "amd64"),
                KeyValue.of("service.name", "checkout"),
                KeyValue.of("host.name", "node-1")));

        assertEquals(CanonicalAttributes.render(a), CanonicalAttributes.render(b),
                "规范化文本必须按键排序");
        assertEquals(CanonicalAttributes.hash(a), CanonicalAttributes.hash(b),
                "同一份属性集合必须算出同一个哈希");
    }

    @Test
    @DisplayName("值类型不同 → 哈希不同（Long 1 / Double 1.0 / String \"1\" 是三种属性）")
    void valueTypeIsPartOfTheIdentity() {
        final List<KeyValue> asLong = Collections.singletonList(
                new KeyValue("k", Long.valueOf(1L)));
        final List<KeyValue> asDouble = Collections.singletonList(
                new KeyValue("k", Double.valueOf(1.0d)));
        final List<KeyValue> asString = Collections.singletonList(
                new KeyValue("k", "1"));

        assertNotEquals(CanonicalAttributes.hash(asLong), CanonicalAttributes.hash(asDouble),
                "Long 1 与 Double 1.0 在 OTLP 里是两种 AnyValue，不能算成同一份");
        assertNotEquals(CanonicalAttributes.hash(asLong), CanonicalAttributes.hash(asString),
                "Long 1 与 String \"1\" 同理");
        assertNotEquals(CanonicalAttributes.hash(asDouble), CanonicalAttributes.hash(asString));
    }

    @Test
    @DisplayName("哈希是 64 位十六进制（SHA-256），且空集合也有稳定取值")
    void hashShape() {
        assertEquals(64, CanonicalAttributes.hash(
                Collections.singletonList(KeyValue.of("a", "b"))).length());
        final String empty = CanonicalAttributes.hash(Collections.<KeyValue>emptyList());
        assertEquals(64, empty.length());
        assertEquals(empty, CanonicalAttributes.hash(Collections.<KeyValue>emptyList()));
        assertTrue(CanonicalAttributes.render(Collections.singletonList(KeyValue.of("a", "b")))
                .contains("a=s:b"), "渲染文本要能给人看：实际 "
                + CanonicalAttributes.render(Collections.singletonList(KeyValue.of("a", "b"))));
    }

    @Test
    @DisplayName("数组值的类型逐个判定，顺序参与哈希")
    void arraysAreOrderSensitive() {
        final List<Object> forward = new ArrayList<Object>(Arrays.asList("a", "b"));
        final List<Object> backward = new ArrayList<Object>(Arrays.asList("b", "a"));
        assertNotEquals(
                CanonicalAttributes.hash(Collections.singletonList(new KeyValue("k", forward))),
                CanonicalAttributes.hash(Collections.singletonList(new KeyValue("k", backward))),
                "数组顺序变了就是另一份属性");
        assertTrue(CanonicalAttributes.render(Collections.singletonList(new KeyValue("k", forward)))
                .contains("[s:a,s:b]"));
    }
}
