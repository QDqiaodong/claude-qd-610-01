package com.recycle.station.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 入参请求。全部是不可变 record，字段上的注解由 controller 的 {@code @Valid} 触发。
 */
public final class Reqs {

    private Reqs() {
    }

    /** 新建 / 修改回收品类 */
    public record CategorySaveReq(
            @NotBlank(message = "品类编号不能为空") String categoryNo,
            @NotBlank(message = "品类名称不能为空") String categoryName,
            @NotBlank(message = "计量单位不能为空") String unit,
            @NotNull(message = "回收单价不能为空") @DecimalMin(value = "0", message = "回收单价不能为负") BigDecimal buyPrice,
            @NotNull(message = "出售单价不能为空") @DecimalMin(value = "0", message = "出售单价不能为负") BigDecimal sellPrice,
            Boolean stored,
            String remark) {
    }

    /** 行内调价：只改当天回收价 / 出售价 */
    public record PriceAdjustReq(
            @NotNull(message = "回收单价不能为空") @DecimalMin(value = "0", message = "回收单价不能为负") BigDecimal buyPrice,
            @NotNull(message = "出售单价不能为空") @DecimalMin(value = "0", message = "出售单价不能为负") BigDecimal sellPrice,
            String reason) {
    }

    /** 过磅单明细行（提交上来的样子：只有品类 + 重量，单价与小计由后端按当日牌价重算） */
    public record LineReq(
            @NotBlank(message = "明细行品类编号不能为空") String categoryNo,
            @NotNull(message = "明细行重量不能为空") @DecimalMin(value = "0.01", message = "明细行重量必须大于 0") BigDecimal weight,
            BigDecimal unitPrice) {
    }

    /** 开一张过磅单 */
    public record TicketCreateReq(
            @NotBlank(message = "客户名称不能为空") @Size(max = 50, message = "客户名称不超过 50 字") String customerName,
            String customerType,
            String weighTime,
            String plateNo,
            @NotNull(message = "毛重不能为空") BigDecimal grossWeight,
            @NotNull(message = "皮重不能为空") BigDecimal tareWeight,
            String remark,
            @NotNull(message = "请至少录入一行明细") @Size(min = 1, message = "请至少录入一行明细") List<LineReq> lines) {
    }

    public record TicketStatusReq(@NotBlank(message = "目标状态不能为空") String status) {
    }

    /** 车辆登记 */
    public record VehicleSaveReq(
            @NotBlank(message = "车牌不能为空") String plateNo,
            @NotBlank(message = "司机不能为空") String driverName,
            String phone,
            @NotNull(message = "额定载重不能为空") BigDecimal loadCapacity,
            String status,
            String remark) {
    }

    /** 车辆状态切换 */
    public record VehicleStatusReq(@NotBlank(message = "目标状态不能为空") String status) {
    }

    /** 结算：一张待结算磅单 → 一张结算单 */
    public record SettleReq(
            @NotBlank(message = "磅单号不能为空") String ticketNo,
            String payMode,
            BigDecimal paidAmount,
            @NotBlank(message = "操作员不能为空") String operator,
            String remark,
            /** 打开结算弹窗时看到的金额版本号；提交时必须仍是当前版本，否则判 VERSION_CONFLICT */
            Integer expectedVersion,
            /** 打开结算弹窗时看到的应付金额；与服务端当前有效金额不一致也判 VERSION_CONFLICT */
            BigDecimal expectedAmount) {
    }

    // ---------------- 模块六：复称复核台 ----------------

    /**
     * 发起复称：只有待结算磅单可发起。三项复称重量允许只带一部分（车辆回皮场景先发起后补录），
     * 带全时直接给出差异判定。clientRequestId 是幂等键，重复点击 / 超时重试原样回传。
     */
    public record ReweighInitiateReq(
            @NotBlank(message = "磅单号不能为空") String ticketNo,
            @NotBlank(message = "发起人不能为空") String initiator,
            String diffReason,
            BigDecimal reGrossWeight,
            BigDecimal reTareWeight,
            BigDecimal reNetWeight,
            String clientRequestId) {
    }

    /** 补录复称后的三项重量 + 明细重新分配数量 + 差异原因 */
    public record ReweighSupplementReq(
            @NotBlank(message = "复称单号不能为空") String reweighNo,
            @NotNull(message = "复称毛重不能为空") BigDecimal reGrossWeight,
            @NotNull(message = "复称皮重不能为空") BigDecimal reTareWeight,
            BigDecimal reNetWeight,
            @NotBlank(message = "差异原因不能为空") String diffReason,
            @NotNull(message = "请提交明细重分配方案") @Size(min = 1, message = "请至少保留一行明细") List<ReweighLineReq> lines,
            String clientRequestId) {
    }

    /** 复称明细行：行号 + 重新分配后的数量；单价只认服务端保存的原单快照 */
    public record ReweighLineReq(
            @NotNull(message = "行号不能为空") Integer lineNo,
            @NotNull(message = "重分配数量不能为空") @DecimalMin(value = "0", message = "重分配数量不能为负") BigDecimal newWeight) {
    }

    /** 现场放行：差异在双阈值内时，由另一名当班人员确认（不能是发起人本人） */
    public record ReweighReleaseReq(
            @NotBlank(message = "复称单号不能为空") String reweighNo,
            @NotBlank(message = "放行人不能为空") String operator,
            String note) {
    }

    /** 待复核记录的复核结论：APPROVED 通过 / REJECTED 驳回（驳回必须填原因） */
    public record ReweighReviewReq(
            @NotBlank(message = "复称单号不能为空") String reweighNo,
            @NotBlank(message = "复核人不能为空") String reviewer,
            @NotBlank(message = "复核结论不能为空") String result,
            String rejectReason,
            String note) {
    }

    /** 差异虽在阈值内但现场有争议时，升级为待复核（仍须另一名当班人员处理） */
    public record ReweighEscalateReq(
            @NotBlank(message = "复称单号不能为空") String reweighNo,
            @NotBlank(message = "操作人不能为空") String operator,
            String reason) {
    }
}
