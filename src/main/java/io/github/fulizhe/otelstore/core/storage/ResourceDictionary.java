package io.github.fulizhe.otelstore.core.storage;

import io.github.fulizhe.otelstore.core.util.ThrottledLogger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import io.github.fulizhe.otelstore.core.model.KeyValue;
import io.github.fulizhe.otelstore.core.model.ResourceDescriptor;

/**
 * {@code Resource} 去重字典表。
 *
 * <p>存在的理由是宽度：实测 {@code Resource} 有 <b>18 个属性</b>且三信号完全相同
 * （ADR-2）。不抽字典就是每行重复 18 个键值；抽了之后 span 行里只剩一个外键。
 *
 * <p><b>以 {@code id} 为准、{@code hash} 只作去重键</b>（ADR-2 原话）。哈希碰撞时
 * 两条 Resource 会共用一行，后来的那条记录因此少几个属性 —— 这是已知且接受的：
 * 为此在读回时保留原始字节（{@code attributes} 列存规范化文本原文）以便发现不一致，
 * 而不去追求"碰撞下也正确"，那需要把整列内容纳入唯一键，等于放弃去重。
 *
 * <p><b>线程模型</b>：{@link LocalStore} 的所有 DB 访问串行在一把锁下，因此本类
 * 不自己加锁；但进程内的哈希缓存用 {@link ConcurrentHashMap}，
 * 因为它是纯函数结果、加锁只是为了 DB 那一半。
 */
final class ResourceDictionary {

    private final Connection conn;
    /** 规范化哈希 → {@code resource_dict.id}。纯缓存，丢了不影响正确性。 */
    private final ConcurrentHashMap<String, Long> idByHash = new ConcurrentHashMap<String, Long>();
    private final AtomicLong internCount = new AtomicLong();
    private final AtomicLong reuseCount = new AtomicLong();
    private final AtomicLong collisionCount = new AtomicLong();

    ResourceDictionary(final Connection conn) {
        this.conn = conn;
    }

    /**
     * 取一份 Resource 的行 id，没有就插一行。
     *
     * @return {@code resource_dict.id}；不会返回 null（失败抛 {@link SQLException} 给上层计数）
     */
    long intern(final ResourceDescriptor descriptor) throws SQLException {
        final List<KeyValue> attributes = descriptor.getAttributes();
        final String canonical = CanonicalAttributes.render(attributes);
        final String hash = CanonicalAttributes.hash(attributes);

        final Long cached = idByHash.get(hash);
        if (cached != null) {
            reuseCount.incrementAndGet();
            return cached.longValue();
        }

        // 缓存未命中可能只是本进程第一次见到它，也可能是另一个线程刚插完。
        // 交给数据库的唯一约束裁决：插成功就用新 id，撞了就用已存在那行的 id。
        try {
            final long id = insert(hash, canonical, attributes.size());
            idByHash.put(hash, Long.valueOf(id));
            internCount.incrementAndGet();
            return id;
        } catch (final SQLException e) {
            final long existing = findByHash(hash);
            if (existing < 0L) {
                throw e;
            }
            if (!existingCanonical(existing).equals(canonical)) {
                // 同一份哈希、不同的原文 = 碰撞。按 ADR-2：计数，不纠正。
                collisionCount.incrementAndGet();
                ThrottledLogger.warn("resource-hash-collision",
                        "resource_dict 出现哈希碰撞：hash=" + hash + " 已被另一份属性占用，本条记录的属性会缺失");
            }
            idByHash.put(hash, Long.valueOf(existing));
            reuseCount.incrementAndGet();
            return existing;
        }
    }

    private long insert(final String hash, final String canonical, final int attrCount) throws SQLException {
        final PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO resource_dict (attributes_hash, attributes, attr_count) VALUES (?, ?, ?)",
                PreparedStatement.RETURN_GENERATED_KEYS);
        try {
            ps.setString(1, hash);
            ps.setString(2, canonical);
            ps.setInt(3, attrCount);
            ps.executeUpdate();
            final ResultSet keys = ps.getGeneratedKeys();
            try {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            } finally {
                keys.close();
            }
            throw new SQLException("resource_dict 插入后拿不到自增 id");
        } finally {
            ps.close();
        }
    }

    private long findByHash(final String hash) throws SQLException {
        final PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM resource_dict WHERE attributes_hash = ?");
        try {
            ps.setString(1, hash);
            final ResultSet rs = ps.executeQuery();
            try {
                return rs.next() ? rs.getLong(1) : -1L;
            } finally {
                rs.close();
            }
        } finally {
            ps.close();
        }
    }

    private String existingCanonical(final long id) throws SQLException {
        final PreparedStatement ps = conn.prepareStatement(
                "SELECT attributes FROM resource_dict WHERE id = ?");
        try {
            ps.setLong(1, id);
            final ResultSet rs = ps.executeQuery();
            try {
                return rs.next() ? rs.getString(1) : "";
            } finally {
                rs.close();
            }
        } finally {
            ps.close();
        }
    }

    /** 字典表行数（读口用）。 */
    int rowCount() throws SQLException {
        final PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM resource_dict");
        try {
            final ResultSet rs = ps.executeQuery();
            try {
                return rs.next() ? rs.getInt(1) : 0;
            } finally {
                rs.close();
            }
        } finally {
            ps.close();
        }
    }

    /** 组合快照。哈希值不进快照 —— 它是实现细节，运维要看的是"字典里几行、撞了几次"。 */
    java.util.Map<String, Object> snapshot() {
        final java.util.Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
        m.put("interned", Long.valueOf(internCount.get()));
        m.put("reused", Long.valueOf(reuseCount.get()));
        m.put("cachedHashes", Integer.valueOf(idByHash.size()));
        m.put("collisions", Long.valueOf(collisionCount.get()));
        return m;
    }
}
