package com.kyle.salesAgent.security;

import com.kyle.salesAgent.service.SalesQueryService;

/**
 * System Prompt 身份注入文案的统一构建器（架构 4.2 引导层）。
 * <p>Controller（对话链路）与评测运行器共用，避免两处文案漂移导致
 * "评测通过、线上行为不同"。文案本身即引导层规则：模型据此主动判断
 * 数据边界，越权问题直接拒答而不是调工具吃 403。
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/2
 */
public final class UserIdentityBuilder {

    /**
     * 按角色生成身份说明。返回文案直接拼入 System Prompt 的【当前用户】段。
     */
    public static String build(UserContext.UserInfo user, SalesQueryService queryService) {
        String regionLabel = user.regionId() == null
                ? "未关联" : queryService.getRegionName(user.regionId());
        return switch (user.role()) {
            case "SALES_REP" -> user.username() + "（销售员）。数据范围：仅本人。他人业绩、大区与全公司汇总均无权查看；用户越权提问时直接说明无权限，并引导其改问自己的数据。用户问\"有没有人/销售员们\"这类群体性业绩或异常问题时，等同为你自己的数据情况，正常调用工具回答";
            case "SALES_MANAGER" -> user.username() + "（销售主管，负责" + regionLabel + "）。数据范围：本大区全部成员。其他大区的人与大区汇总均无权查看；用户越权提问时直接说明无权限";
            case "SALES_DIRECTOR" -> user.username() + "（销售总监）。数据范围：全公司所有数据";
            default -> user.username() + "（角色未知）。数据范围：仅本人";
        };
    }

    private UserIdentityBuilder() {
    }
}
