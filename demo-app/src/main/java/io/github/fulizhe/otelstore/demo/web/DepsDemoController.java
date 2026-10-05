package io.github.fulizhe.otelstore.demo.web;

import io.github.fulizhe.otelstore.demo.deps.DepStatus;
import io.github.fulizhe.otelstore.demo.deps.DepsDemoService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 依赖样例的入口。
 *
 * <p><b>降级时仍然返回 HTTP 200</b>，这不是偷懒而是 ADR-7 的口径：
 * 「这个依赖没起来」是可预期的正常状态（MySQL 那一跳尤其如此），
 * 500 会让调用方分不清"依赖没起来"与"服务坏了"。
 */
@RestController
@RequestMapping("/demo/deps")
public class DepsDemoController {

    private final DepsDemoService deps;

    @Autowired
    public DepsDemoController(final DepsDemoService deps) {
        this.deps = deps;
    }

    /** 五项状态，一眼看出哪些能用、哪些不能用、为什么。 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        final List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        int ready = 0;
        for (final DepStatus s : deps.status()) {
            final Map<String, Object> one = new LinkedHashMap<String, Object>();
            one.put("key", s.key());
            one.put("title", s.title());
            // embedded 让"哪一项需要外部进程"一眼可见 —— 本项目里只有 MySQL 是 false
            one.put("embedded", Boolean.valueOf(s.embedded()));
            one.put("ready", Boolean.valueOf(s.ready()));
            one.put("detail", s.detail());
            items.add(one);
            if (s.ready()) {
                ready++;
            }
        }
        final Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("dependencies", items);
        m.put("readyCount", Integer.valueOf(ready));
        m.put("totalCount", Integer.valueOf(items.size()));
        // 少的那几跳要写在这里，而不是让人对着链路图猜"是埋点没挂上还是库没起来"
        m.put("note", "ready=false 的那一项不会出现在链路图上；这是预期行为，不是故障（ADR-7）");
        return m;
    }

    @PostMapping("/h2")
    public Map<String, Object> h2() {
        return deps.callH2();
    }

    @PostMapping("/redis")
    public Map<String, Object> redis() {
        return deps.callRedis();
    }

    @PostMapping("/kafka")
    public Map<String, Object> kafka() {
        return deps.callKafka();
    }

    @PostMapping("/grpc")
    public Map<String, Object> grpc() {
        return deps.callGrpc();
    }

    @PostMapping("/mysql")
    public Map<String, Object> mysql() {
        return deps.callMysql();
    }

    /**
     * 端到端验收的主路径：一次打五跳并返回 traceId。
     *
     * <p>拷返回里的 traceId 去 {@code /api/traces?traceId=}，就能对账"五跳都在库里"。
     */
    @PostMapping("/all")
    public Map<String, Object> all() {
        return deps.callAll();
    }
}