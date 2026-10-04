package io.github.fulizhe.otelstore.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一份去重前的 {@code Resource}：白名单过滤后的属性列表。
 *
 * <p>{@code agentext} 负责把 OTel 的 {@code Resource} 翻成本类型，
 * 并且<b>在翻译时就完成白名单过滤</b> —— 理由见 ADR-2「Resource 属性白名单」：
 * 实测属性里有 {@code process.command_line}（完整命令行），落库等于把
 * {@code -Dxxx.token=} 写进本地文件。这类判断必须在数据离开采集侧之前做完，
 * 不能寄望存储层"记得过滤"。
 *
 * <p>规范化与哈希在 {@code core} 的字典表里做（ADR-2：字典表按规范化哈希去重）。
 * 也就是说本类只负责"带过来什么"，不负责"算成什么"。
 */
public final class ResourceDescriptor {

    private static final ResourceDescriptor EMPTY =
            new ResourceDescriptor(Collections.<KeyValue>emptyList());

    private final List<KeyValue> attributes;

    public ResourceDescriptor(final List<KeyValue> attributes) {
        final List<KeyValue> copy = new ArrayList<KeyValue>();
        if (attributes != null) {
            for (int i = 0; i < attributes.size(); i++) {
                if (attributes.get(i) != null) {
                    copy.add(attributes.get(i));
                }
            }
        }
        this.attributes = Collections.unmodifiableList(copy);
    }

    /** 无属性的 Resource（理论上不该出现，但不该因此让一条记录写不进去）。 */
    public static ResourceDescriptor empty() {
        return EMPTY;
    }

    public List<KeyValue> getAttributes() {
        return attributes;
    }

    public int size() {
        return attributes.size();
    }

    @Override
    public String toString() {
        return "ResourceDescriptor" + attributes;
    }
}
