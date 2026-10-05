package io.github.fulizhe.otelstore.demo.deps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 降级口径的形状：<b>探不通不崩、进程照常起</b>。
 *
 * <p>这些全是纯内存的（不起真依赖），所以能在进程内跑。
 * 真实依赖的起停不在这里验 —— 那由用户在真机上验（见 demo-app README 的验收清单）。
 */
class DepsRegistryTest {

    private static DependencyProbe throwing(final String key) {
        return new DependencyProbe() {
            @Override
            public String key() {
                return key;
            }

            @Override
            public String title() {
                return key;
            }

            @Override
            public boolean embedded() {
                return true;
            }

            @Override
            public DepStatus probe() {
                throw new IllegalStateException("模拟探测失败");
            }
        };
    }

    private static DependencyProbe ready(final String key) {
        return new DependencyProbe() {
            @Override
            public String key() {
                return key;
            }

            @Override
            public String title() {
                return key;
            }

            @Override
            public boolean embedded() {
                return true;
            }

            @Override
            public DepStatus probe() {
                return DepStatus.ready(key, key, true, "好了");
            }
        };
    }

    /** key 本身取不到时的探针 —— 兜底造出来的状态也得有个名字可用。 */
    private static DependencyProbe brokenKey(final String detail) {
        return new DependencyProbe() {
            @Override
            public String key() {
                throw new IllegalStateException(detail);
            }

            @Override
            public String title() {
                return detail;
            }

            @Override
            public boolean embedded() {
                return false;
            }

            @Override
            public DepStatus probe() {
                throw new IllegalStateException(detail);
            }
        };
    }

    @Test
    @DisplayName("探测抛异常要被压成 not-ready，而不是把应用搞崩")
    void throwingProbeBecomesNotReady() {
        final DepsRegistry reg = new DepsRegistry(Arrays.asList(throwing("redis")));
        final DepStatus s = reg.get("redis");
        assertNotNull(s);
        assertFalse(s.ready(), "探测抛异常不能让应用起不来");
        assertTrue(s.detail().contains("模拟探测失败"), "要说清为什么：" + s.detail());
    }

    @Test
    @DisplayName("探测里 NPE 那种意外也要兜住 —— 它同样该被压成'这个依赖不可用'")
    void throwingThrowableAlsoBecomesNotReady() {
        final DepsRegistry reg = new DepsRegistry(Arrays.asList(new DependencyProbe() {
            @Override
            public String key() {
                return "jdbc";
            }

            @Override
            public String title() {
                return "JDBC";
            }

            @Override
            public boolean embedded() {
                return true;
            }

            @Override
            public DepStatus probe() {
                throw new NoClassDefFoundError("redis/clients/jedis/Jedis");
            }
        }));
        assertFalse(reg.all().get(0).ready(), "NoClassDefFoundError 也该被压成降级");
    }

    @Test
    @DisplayName("连 key() 都抛异常时也要兜住，不能让应用起不来")
    void brokenKeyStillProducesAStatus() {
        final DepsRegistry reg = new DepsRegistry(Arrays.asList(brokenKey("连名字都取不到")));
        assertEquals(1, reg.all().size(), "降级项仍然要占一格，否则列表里看不出少了东西");
        assertFalse(reg.all().get(0).ready());
        assertTrue(reg.all().get(0).detail().contains("连名字都取不到"), reg.all().get(0).detail());
    }

    @Test
    @DisplayName("探测返回 null 要被压成 not-ready，而不是留下一个空状态")
    void nullProbeBecomesNotReady() {
        final DepsRegistry reg = new DepsRegistry(Arrays.asList(new DependencyProbe() {
            @Override
            public String key() {
                return "h2";
            }

            @Override
            public String title() {
                return "H2";
            }

            @Override
            public boolean embedded() {
                return true;
            }

            @Override
            public DepStatus probe() {
                return null;
            }
        }));
        final DepStatus s = reg.all().get(0);
        assertFalse(s.ready());
        assertTrue(s.detail().contains("null"), "要说清是探测实现有 bug：" + s.detail());
    }

    @Test
    @DisplayName("探测返回的 key 与探针自己的 key 不一致时，以探针的为准")
    void registryKeyWinsOverProbeKey() {
        final DepsRegistry reg = new DepsRegistry(Arrays.asList(new DependencyProbe() {
            @Override
            public String key() {
                return "h2";
            }

            @Override
            public String title() {
                return "H2";
            }

            @Override
            public boolean embedded() {
                return true;
            }

            @Override
            public DepStatus probe() {
                return DepStatus.ready("拼错了", "H2", true, "好了");
            }
        }));
        assertNull(reg.get("拼错了"), "不能让同一个状态在两份口径里各有一个名字");
        assertNotNull(reg.get("h2"));
    }

    @Test
    @DisplayName("状态按注册顺序返回 —— 页面上的顺序就是探测的顺序")
    void orderFollowsRegistration() {
        final DepsRegistry reg = new DepsRegistry(
                Arrays.asList(ready("h2"), ready("redis"), ready("kafka")));
        final List<DepStatus> all = reg.all();
        assertEquals(3, all.size());
        assertEquals("h2", all.get(0).key());
        assertEquals("redis", all.get(1).key());
        assertEquals("kafka", all.get(2).key());
    }

    @Test
    @DisplayName("查一个没注册过的 key 返回 null，而不是伪装成'不可用'")
    void unknownKeyIsNullNotNotReady() {
        final DepsRegistry reg = new DepsRegistry(Arrays.asList(ready("h2")));
        assertNull(reg.get("kafka"), "没有这一项就说没有，别混成'不可用'");
    }
}