package com.recycle.station.domain;

import java.io.Serializable;
import java.util.Objects;

/**
 * 过磅单明细行的复合主键：磅单号 + 行号。
 * <p>
 * {@code @IdClass} 要求：实现 {@link Serializable}、有无参构造、重写 {@code equals}/{@code hashCode}，
 * 且字段名与实体里 {@code @Id} 字段同名同类型。
 */
public class WeighTicketLineId implements Serializable {

    private static final long serialVersionUID = 1L;

    private String ticketNo;

    private Integer lineNo;

    public WeighTicketLineId() {
    }

    public WeighTicketLineId(String ticketNo, Integer lineNo) {
        this.ticketNo = ticketNo;
        this.lineNo = lineNo;
    }

    public String getTicketNo() {
        return ticketNo;
    }

    public void setTicketNo(String ticketNo) {
        this.ticketNo = ticketNo;
    }

    public Integer getLineNo() {
        return lineNo;
    }

    public void setLineNo(Integer lineNo) {
        this.lineNo = lineNo;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        WeighTicketLineId that = (WeighTicketLineId) o;
        return Objects.equals(ticketNo, that.ticketNo) && Objects.equals(lineNo, that.lineNo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ticketNo, lineNo);
    }

    @Override
    public String toString() {
        return ticketNo + "#" + lineNo;
    }
}
