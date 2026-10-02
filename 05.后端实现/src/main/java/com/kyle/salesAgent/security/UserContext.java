package com.kyle.salesAgent.security;

/**
 * 当前用户身份上下文：拦截器写入，Service 层读取后注入权限过滤（架构 3.2.2）。
 * <p>生命周期：preHandle 里 {@link #set} → 链路中 {@link #get} → afterCompletion 里 {@link #clear}。
 * <p>请求结束后必须调用 {@link #clear}：Tomcat 线程复用，不清则下一个请求会读到
 * 上一用户的身份。{@link #get} 拿到 null 时必须拒绝查询，不能当成"全公司"处理
 * （fail-closed，架构 4.2）。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/1 15:52
 */
public class UserContext {

    /** 全应用唯一的 ThreadLocal 入口；真正的值存在各线程内部，每条线程各见各的。 */
    private static final ThreadLocal<UserInfo> HOLDER = new ThreadLocal<>();

    /**
     * 当前登录用户。字段只能来自服务端（Token 解析/用户表），前端和模型传的值不得写入。
     *
     * @param userId   用户 ID（审计日志用）
     * @param username 姓名（展示用）
     * @param role     SALES_REP / SALES_MANAGER / SALES_DIRECTOR（技术债 #3：后续换 enum）
     * @param regionId 大区 ID（主管角色的过滤来源）
     * @param repId    销售员 ID（销售员角色的过滤来源；主管/总监可为 null）
     */
    public record UserInfo(Long userId, String username, String role, Long regionId, Long repId) {

        /** 角色判定收敛在 record 上，Service/工具层不写角色字符串字面量（换 enum 时只改这里）。 */
        public boolean isRep() { return "SALES_REP".equals(role); }

        public boolean isManager() { return "SALES_MANAGER".equals(role); }

        public boolean isDirector() { return "SALES_DIRECTOR".equals(role); }
    }

    public static void set(UserInfo info) { HOLDER.set(info); }

    /** 可能返回 null（跨线程未传递等），调用方必须按 fail-closed 处理。 */
    public static UserInfo get() { return HOLDER.get(); }

    public static void clear() { HOLDER.remove(); }

    /** 是否总监（查全公司）。null 时返回 false，安全侧。 */
    public static boolean isDirector() {
        UserInfo u = get();
        return u != null && u.isDirector();
    }

    /** 是否主管（查本大区）。 */
    public static boolean isManager() {
        UserInfo u = get();
        return u != null && u.isManager();
    }

    /** 是否销售员（只能查自己）。 */
    public static boolean isRep() {
        UserInfo u = get();
        return u != null && u.isRep();
    }

    /**
     * 缓存 Key 的权限维度："角色|大区|人"。所有 @Cacheable 的 Key 都必须拼上它，
     * 这样不同角色/不同大区的人即使问法完全一样，也绝不会命中同一条缓存（架构 4.3）。
     * 未登录/身份丢失时返回 ANONYMOUS——该标记下的查询必然被 fail-closed 拒绝，
     * 缓存里永远不会出现 ANONYMOUS 条目。
     */
    public static String cacheScopeTag() {
        UserInfo u = get();
        return u == null ? "ANONYMOUS" : u.role() + "|" + u.regionId() + "|" + u.repId();
    }
}
