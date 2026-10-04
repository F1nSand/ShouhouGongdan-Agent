package com.aftercare.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("mock")
public class MockChatProvider implements ChatProvider {
    private static final Pattern ORDER_NO = Pattern.compile("\\b[A-Z]{2}\\d{9,18}\\b");
    private static final Pattern SKU_CODE = Pattern.compile("\\bSKU[-A-Z0-9]{2,30}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern UUID_VALUE = Pattern.compile(
            "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");

    @Override
    public AgentPlan plan(String userMessage) {
        String message = userMessage == null ? "" : userMessage;
        var intents = new ArrayList<AgentPlan.Intent>();
        String orderNo = find(ORDER_NO, message);
        String skuCode = find(SKU_CODE, message);
        String lowered = message.toLowerCase(java.util.Locale.ROOT);

        if (orderNo != null) {
            intents.add(AgentPlan.Intent.ORDER_QUERY);
        }
        if (containsAny(lowered, "物流", "快递", "未收到", "没收到", "配送", "签收", "承运商", "运单",
                "物流轨迹", "配送承诺", "延迟", "错投")) {
            intents.add(AgentPlan.Intent.LOGISTICS);
        }
        if (containsAny(lowered, "政策", "退货", "退款", "换货", "保修", "维修", "退换", "售后政策")) {
            intents.add(AgentPlan.Intent.POLICY_QA);
        }
        boolean transferRequest = skuCode != null
                && containsAny(lowered, "调拨", "补货", "补充", "给出建议");
        if (skuCode != null && (containsAny(lowered, "库存", "有货", "缺货", "可调配") || transferRequest)) {
            intents.add(AgentPlan.Intent.INVENTORY_QUERY);
        }
        UUID[] uuidValues = findUuids(message);
        if (transferRequest) {
            intents.add(AgentPlan.Intent.TRANSFER_RECOMMENDATION);
        }
        if (transferRequest && uuidValues.length >= 3
                && containsAny(lowered, "调拨审批草稿", "调拨申请草稿", "申请调拨")) {
            intents.add(AgentPlan.Intent.TRANSFER_DRAFT);
        }
        boolean explicitlyRequestsTicket = containsAny(lowered, "工单", "投诉", "故障", "售后处理", "待确认记录");
        if (explicitlyRequestsTicket) {
            intents.add(AgentPlan.Intent.TICKET_DRAFT);
        }
        if (containsAny(lowered, "申请退款", "帮我退款", "我要退款", "发起退款")) {
            intents.add(AgentPlan.Intent.REFUND_DRAFT);
        }
        if (intents.isEmpty()) {
            intents.add(AgentPlan.Intent.UNKNOWN);
        }
        String title = message.strip().length() > 120 ? message.strip().substring(0, 120) : message.strip();
        String priority = containsAny(lowered, "紧急", "马上", "尽快", "严重") ? "HIGH" : "NORMAL";
        return new AgentPlan(intents, orderNo, skuCode,
                skuCode == null || uuidValues.length == 0 ? null : uuidValues[0],
                uuidValues.length < 2 ? null : uuidValues[1],
                parseQuantity(message), null, parseAmountCents(message), title.isBlank() ? "售后问题" : title,
                message, priority, "用户申请退款", uuidValues.length < 3 ? null : uuidValues[2]);
    }

    private static String find(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group() : null;
    }

    private static UUID[] findUuids(String value) {
        Matcher matcher = UUID_VALUE.matcher(value);
        var result = new ArrayList<UUID>();
        while (matcher.find() && result.size() < 3) {
            result.add(UUID.fromString(matcher.group()));
        }
        return result.toArray(UUID[]::new);
    }

    private static Integer parseQuantity(String value) {
        Matcher matcher = Pattern.compile("(?:数量|调拨|补货|补充)\\s*[:：]?\\s*(\\d{1,6})").matcher(value);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    private static Long parseAmountCents(String value) {
        Matcher matcher = Pattern.compile("(?:退款|退)\\s*(\\d{1,8}(?:\\.\\d{1,2})?)\\s*(?:元|块)").matcher(value);
        if (!matcher.find()) {
            return null;
        }
        try {
            return new BigDecimal(matcher.group(1)).movePointRight(2)
                    .setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        }
        catch (ArithmeticException | NumberFormatException invalid) {
            return null;
        }
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }
}
