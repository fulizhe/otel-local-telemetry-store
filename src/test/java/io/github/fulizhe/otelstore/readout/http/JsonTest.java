package io.github.fulizhe.otelstore.readout.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JSON 编码器的契约 —— 它是手写的，所以边界必须逐条钉住。
 *
 * <p>这里测的是**产出的文本**，不是内部实现：断言"给定这个值，编码出来是这串字符"。
 * 一旦改成"解析回来再比较"就失去了意义 —— 那会把编码错误与解析错误互相抵消掉。
 */
class JsonTest {

    @Test
    @DisplayName("标量与容器")
    void scalarsAndContainers() {
        assertEquals("null", Json.write(null));
        assertEquals("true", Json.write(Boolean.TRUE));
        assertEquals("false", Json.write(Boolean.FALSE));
        assertEquals("42", Json.write(Integer.valueOf(42)));
        assertEquals("42", Json.write(Long.valueOf(42L)));
        assertEquals("1.5", Json.write(Double.valueOf(1.5d)));
        assertEquals("\"hi\"", Json.write("hi"));

        assertEquals("{}", Json.write(Collections.emptyMap()));
        assertEquals("[]", Json.write(Collections.emptyList()));
        assertEquals("[1,2]", Json.write(Arrays.asList(Integer.valueOf(1), Integer.valueOf(2))));
    }

    @Test
    @DisplayName("Map 保持插入顺序，键一律当字符串")
    void mapsKeepOrderAndStringifyKeys() {
        final Map<Object, Object> m = new LinkedHashMap<Object, Object>();
        m.put("b", Integer.valueOf(2));
        m.put(Integer.valueOf(7), "seven");
        m.put("a", null);
        assertEquals("{\"b\":2,\"7\":\"seven\",\"a\":null}", Json.write(m));
    }

    @Test
    @DisplayName("嵌套结构")
    void nested() {
        final Map<String, Object> inner = new LinkedHashMap<String, Object>();
        inner.put("k", "v");
        final List<Object> list = new ArrayList<Object>();
        list.add(inner);
        list.add(Arrays.asList("a", "b"));
        assertEquals("[{\"k\":\"v\"},[\"a\",\"b\"]]", Json.write(list));
    }

    @Test
    @DisplayName("NaN 与 Infinity 不是合法 JSON —— 必须落成 null，绝不能原样输出")
    void nanAndInfinityBecomeNull() {
        // 这是最容易漏的一条：指标的非标量形态存的就是 NaN，
        // 原样写出去得到的是一段解析器会拒绝的文本 —— 整个响应就废了
        assertEquals("null", Json.write(Double.valueOf(Double.NaN)));
        assertEquals("null", Json.write(Double.valueOf(Double.POSITIVE_INFINITY)));
        assertEquals("null", Json.write(Double.valueOf(Double.NEGATIVE_INFINITY)));
        assertEquals("null", Json.write(Float.valueOf(Float.NaN)));
        assertEquals("{\"v\":null}", Json.write(Collections.singletonMap("v", Double.valueOf(Double.NaN))));

        final Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", Double.valueOf(0.0d));
        m.put("bad", Double.valueOf(Double.NaN));
        assertEquals("{\"ok\":0.0,\"bad\":null}", Json.write(m));
    }

    @Test
    @DisplayName("字符串转义：引号、反斜杠、控制字符")
    void stringEscaping() {
        assertEquals("\"a\\\"b\"", Json.write("a\"b"));
        assertEquals("\"a\\\\b\"", Json.write("a\\b"));
        assertEquals("\"a\\nb\"", Json.write("a\nb"));
        assertEquals("\"a\\rb\"", Json.write("a\rb"));
        assertEquals("\"a\\tb\"", Json.write("a\tb"));
        assertEquals("\"a\\bb\"", Json.write("a\bb"));
        assertEquals("\"a\\fb\"", Json.write("a\fb"));
        // 没有简写的控制字符必须用 U+ 形式 —— 不转义就是一段解析器会拒绝的文本
        assertEquals("\"a\\u0000b\"", Json.write("a\u0000b"));
        assertEquals("\"a\\u001fb\"", Json.write("a\u001fb"));
        // 斜杠与单引号在 JSON 里不需要转义
        assertEquals("\"a/b'c\"", Json.write("a/b'c"));
    }

    @Test
    @DisplayName("非 ASCII 原样输出，不逐字符转义")
    void nonAsciiStaysReadable() {
        assertEquals("\"订单 42\"", Json.write("订单 42"));
        // 输入里的字面「反斜杠 + u」不能被当成转义序列吃掉 —— 它就是两个普通字符
        assertEquals("\"\\\\u4e2d 是字面反斜杠-u\"", Json.write("\\u4e2d 是字面反斜杠-u"));
    }

    @Test
    @DisplayName("遇到不认识的对象直接抛错，不静默输出空对象")
    void unsupportedTypeThrows() {
        final Object custom = new Object() {
            @Override
            public String toString() {
                return "一个自定义对象";
            }
        };
        final IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
                    @Override
                    public void execute() {
                        Json.write(custom);
                    }
                });
        assertTrue(e.getMessage().contains("不支持的 JSON 类型"), e.getMessage());

        // 数组里出现也一样要炸 —— 半截合法半截缺失的响应比整段失败更难查
        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                Json.write(Collections.singletonList(custom));
            }
        });
    }
}