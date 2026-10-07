package com.videostep.locator.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videostep.locator.entity.Invoice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface InvoiceRepository extends BaseMapper<Invoice> {
    @Select("SELECT COUNT(*) FROM invoices WHERE org_id = #{orgId} AND description = #{description}")
    long countByOrgAndDescription(@Param("orgId") Long orgId, @Param("description") String description);
}
