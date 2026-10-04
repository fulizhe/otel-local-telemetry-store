package io.github.fulizhe.otelstore.agentext;

import io.github.fulizhe.otelstore.core.model.KeyValue;
import io.github.fulizhe.otelstore.core.model.ResourceDescriptor;
import io.opentelemetry.sdk.resources.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * {@code Resource} → 白名单过滤后的 {@link ResourceDescriptor}。
 *
 * <p><b>白名单是这里唯一的密钥防线</b>（ADR-2「Resource 属性白名单」）。实测 {@code Resource}
 * 有 18 个属性且三信号完全相同，其中 {@code process.command_line} 是<b>完整命令行</b> ——
 * 命令行里带 {@code -Dxxx.token=} 或 {@code -Ddb.password=}，落库就等于把密钥写进本地文件。
 *
 * <p>用白名单而不是黑名单，是因为要挡的不是"某几个已知危险的键"，而是"所有我们没预期过的键"：
 * 语义约定会变、第三方库会加属性，只有"列出确实要存的"才挡得住未来。
 *
 * <p>过滤在<b>翻译期</b>做，不是在存储层做。理由：载荷（环形文件）与表头（H2）是两条落盘路径，
 * 在存储层过滤就得写两遍；漏一遍就等于密钥照旧进了另一个文件。翻译期只留一个出口。
 *
 * <p>明确排除的键（ADR-2 原话）：{@code process.command_line}、{@code process.executable.path}、
 * {@code process.runtime.*}、{@code process.pid}、{@code host.ip}、{@code os.description} ——
 * 路径、命令行、IP 都不进本地库。
 */
final class ResourceMapper {

    /**
     * 落库白名单。
     *
     * <p>白名单之外的键若将来要支持，走配置项追加，<b>不改代码</b>（ADR-2）。
     * 用不可变 List 而不是数组：{@code contains} 在每条记录的热路径上。
     */
    static final List<String> WHITELIST = Collections.unmodifiableList(Arrays.asList(
            "service.name",
            "service.namespace",
            "service.instance.id",
            "service.version",
            "deployment.environment.name",
            "host.name",
            "host.arch",
            "os.type"));

    private ResourceMapper() {
    }

    /** OTel {@code Resource} → 表头用的描述子（已过滤）。 */
    static ResourceDescriptor toDescriptor(final Resource resource) {
        if (resource == null) {
            return ResourceDescriptor.empty();
        }
        final List<KeyValue> filtered = OtelAttributes.toCoreWhitelisted(resource.getAttributes(), WHITELIST);
        return filtered.isEmpty() ? ResourceDescriptor.empty() : new ResourceDescriptor(filtered);
    }

    /**
     * 已过滤的属性 → OTLP {@code Resource}，供载荷用。
     *
     * <p>入参是 {@link #toDescriptor} 的结果而<b>不是</b>原始 {@code Resource}：载荷同样不许
     * 带命令行进来。这条容易写错 —— 载荷"只是给人看的详情"，但它落在磁盘上、活得比进程久。
     *
     * <p>直接从 {@link KeyValue} 拼 protobuf，不绕回 OTel 的 {@code Attributes}：绕一圈就得把
     * 类型猜回去（表头里 {@code Long} 是从 {@code Integer} 升上来的），猜错一次就是一处静默偏差。
     */
    static io.opentelemetry.proto.resource.v1.Resource toProto(final ResourceDescriptor descriptor) {
        final List<KeyValue> attributes = descriptor.getAttributes();
        final List<io.opentelemetry.proto.common.v1.KeyValue> out =
                new ArrayList<io.opentelemetry.proto.common.v1.KeyValue>(attributes.size());
        for (int i = 0; i < attributes.size(); i++) {
            final KeyValue kv = attributes.get(i);
            out.add(io.opentelemetry.proto.common.v1.KeyValue.newBuilder()
                    .setKey(kv.getKey())
                    .setValue(OtelAttributes.toAnyValue(kv.getValue()))
                    .build());
        }
        return io.opentelemetry.proto.resource.v1.Resource.newBuilder().addAllAttributes(out).build();
    }
}
