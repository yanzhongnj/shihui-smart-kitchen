package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.PaymentOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PaymentOrderRepository extends BaseMapper<PaymentOrder> {
    @Select("SELECT * FROM payment_orders WHERE invoice_id = #{invoiceId} AND status = 'created' ORDER BY id DESC LIMIT 1")
    PaymentOrder findCreatedByInvoice(@Param("invoiceId") Long invoiceId);
}
