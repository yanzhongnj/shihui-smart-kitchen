package com.videostep.locator.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videostep.locator.entity.*;
import com.videostep.locator.repository.*;
import com.videostep.locator.service.creation.CreationJsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A2 厂商 B2B2C：合同 / 设备 SKU / 出厂绑定码 / 菜谱内容授权 / C 端扫码激活。
 * 本模块为「厨电厂商白标」的账号与授权底座；设备指令下发能力见《智能厨电中控_技术设计文档》P0–P3。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VendorService {

    private final VendorContractRepository contractRepository;
    private final DeviceSkuRepository skuRepository;
    private final DeviceBindingRepository bindingRepository;
    private final ContentLicenseRepository licenseRepository;
    private final OrgRepository orgRepository;
    private final UserService userService;
    private final AiCreationRepository creationRepository;
    private final AiCreationVideoRepository creationVideoRepository;

    // ---------------- 厂商合同 ----------------

    @Transactional
    public VendorContract createContract(Long orgId, String contractNo, Integer deviceQuota, String status) {
        requireOrg(orgId);
        if (contractNo == null || contractNo.isBlank()) contractNo = "VC-" + Long.toHexString(System.currentTimeMillis());
        if (deviceQuota == null || deviceQuota < 0) deviceQuota = 0;
        VendorContract c = VendorContract.builder()
                .orgId(orgId)
                .contractNo(contractNo)
                .status(status == null || status.isBlank() ? VendorContract.STATUS_ACTIVE : status)
                .deviceQuota(deviceQuota)
                .startedAt(LocalDateTime.now())
                .build();
        contractRepository.insert(c);
        return c;
    }

    public List<VendorContract> listContracts(Long orgId) {
        return contractRepository.selectList(new LambdaQueryWrapper<VendorContract>()
                .eq(orgId != null, VendorContract::getOrgId, orgId)
                .orderByDesc(VendorContract::getId));
    }

    @Transactional
    public VendorContract setContractStatus(Long contractId, String status) {
        VendorContract c = requireContract(contractId);
        c.setStatus(status);
        contractRepository.updateById(c);
        return c;
    }

    // ---------------- 设备 SKU 与绑定码 ----------------

    @Transactional
    public DeviceSku createSku(Long vendorOrgId, String sku, String deviceType, String capabilities, String defaultGroup) {
        requireOrg(vendorOrgId);
        if (sku == null || sku.isBlank()) throw new IllegalArgumentException("SKU 型号不能为空");
        DeviceSku s = DeviceSku.builder()
                .vendorOrgId(vendorOrgId)
                .sku(sku.trim())
                .deviceType(deviceType == null || deviceType.isBlank() ? "oven" : deviceType)
                .capabilities(capabilities)
                .defaultRecipeGroup(defaultGroup)
                .status(1)
                .build();
        skuRepository.insert(s);
        return s;
    }

    public List<DeviceSku> listSkus(Long vendorOrgId) {
        return skuRepository.selectList(new LambdaQueryWrapper<DeviceSku>()
                .eq(vendorOrgId != null, DeviceSku::getVendorOrgId, vendorOrgId)
                .orderByDesc(DeviceSku::getId));
    }

    /** 为某 SKU 批量生成出厂绑定码 */
    @Transactional
    public List<String> generateBindCodes(Long skuId, int count) {
        if (count <= 0 || count > 1000) throw new IllegalArgumentException("生成数量须在 1~1000 之间");
        DeviceSku sku = skuRepository.findEnabledById(skuId);
        if (sku == null) throw new IllegalArgumentException("SKU 不存在或未启用");
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String code = "BC-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            DeviceBinding b = DeviceBinding.builder()
                    .skuId(skuId)
                    .vendorOrgId(sku.getVendorOrgId())
                    .bindCode(code)
                    .status(DeviceBinding.STATUS_UNBOUND)
                    .build();
            bindingRepository.insert(b);
            codes.add(code);
        }
        log.info("生成绑定码 {} 个 sku={}", count, skuId);
        return codes;
    }

    public Map<String, Object> listBindings(Long vendorOrgId, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        LambdaQueryWrapper<DeviceBinding> qw = new LambdaQueryWrapper<DeviceBinding>()
                .eq(vendorOrgId != null, DeviceBinding::getVendorOrgId, vendorOrgId)
                .orderByDesc(DeviceBinding::getId);
        long total = bindingRepository.selectCount(qw);
        qw.last("LIMIT " + (safePage * safeSize) + ", " + safeSize);
        List<Map<String, Object>> items = new ArrayList<>();
        for (DeviceBinding b : bindingRepository.selectList(qw)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.getId());
            m.put("bindCode", b.getBindCode());
            m.put("skuId", b.getSkuId());
            m.put("status", b.getStatus());
            m.put("boundAt", b.getBoundAt());
            m.put("createdAt", b.getCreatedAt());
            if (b.getUserId() != null) {
                User u = userService.getById(b.getUserId());
                m.put("username", u == null ? null : u.getUsername());
            }
            DeviceSku sku = skuRepository.selectById(b.getSkuId());
            m.put("sku", sku == null ? null : sku.getSku());
            items.add(m);
        }
        return Map.of("items", items, "total", total);
    }

    /** 激活统计 */
    public Map<String, Object> activationStats(Long vendorOrgId) {
        long total = bindingRepository.selectCount(new LambdaQueryWrapper<DeviceBinding>()
                .eq(vendorOrgId != null, DeviceBinding::getVendorOrgId, vendorOrgId));
        long bound = bindingRepository.countBoundByOrg(vendorOrgId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total);
        m.put("bound", bound);
        m.put("unbound", total - bound);
        m.put("skuCount", skuRepository.selectCount(new LambdaQueryWrapper<DeviceSku>()
                .eq(vendorOrgId != null, DeviceSku::getVendorOrgId, vendorOrgId)));
        return m;
    }

    // ---------------- 厂商总览（管理端表格式列表） ----------------

    /** 所有企业 + 各厂商业务计数（合同/SKU/绑定码/授权），供管理端厂商表格 */
    public List<Map<String, Object>> listVendorSummary() {
        List<Org> orgs = orgRepository.selectList(new LambdaQueryWrapper<Org>().orderByDesc(Org::getId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Org o : orgs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", o.getId());
            m.put("name", o.getName());
            m.put("code", o.getCode());
            m.put("planType", o.getPlanType());
            m.put("status", o.getStatus());
            m.put("contractCount", contractRepository.selectCount(
                    new LambdaQueryWrapper<VendorContract>().eq(VendorContract::getOrgId, o.getId())));
            m.put("skuCount", skuRepository.selectCount(
                    new LambdaQueryWrapper<DeviceSku>().eq(DeviceSku::getVendorOrgId, o.getId())));
            m.put("bindingTotal", bindingRepository.selectCount(
                    new LambdaQueryWrapper<DeviceBinding>().eq(DeviceBinding::getVendorOrgId, o.getId())));
            m.put("bindingBound", bindingRepository.countBoundByOrg(o.getId()));
            m.put("licenseCount", licenseRepository.selectCount(
                    new LambdaQueryWrapper<ContentLicense>().eq(ContentLicense::getVendorOrgId, o.getId())));
            out.add(m);
        }
        return out;
    }

    // ---------------- 菜谱内容授权 ----------------

    @Transactional
    public ContentLicense addLicense(Long vendorOrgId, String recipeGroup) {
        requireOrg(vendorOrgId);
        if (recipeGroup == null || recipeGroup.isBlank()) throw new IllegalArgumentException("菜谱分组不能为空");
        ContentLicense existing = licenseRepository.findByOrgAndGroup(vendorOrgId, recipeGroup);
        if (existing != null) {
            existing.setStatus(ContentLicense.STATUS_ACTIVE);
            licenseRepository.updateById(existing);
            return existing;
        }
        ContentLicense lic = ContentLicense.builder()
                .vendorOrgId(vendorOrgId)
                .recipeGroup(recipeGroup.trim())
                .status(ContentLicense.STATUS_ACTIVE)
                .licensedAt(LocalDateTime.now())
                .build();
        licenseRepository.insert(lic);
        return lic;
    }

    @Transactional
    public ContentLicense setLicenseStatus(Long licenseId, String status) {
        ContentLicense lic = licenseRepository.selectById(licenseId);
        if (lic == null) throw new IllegalArgumentException("授权不存在");
        lic.setStatus(status);
        licenseRepository.updateById(lic);
        return lic;
    }

    public List<Map<String, Object>> listLicenses(Long vendorOrgId) {
        List<ContentLicense> list = licenseRepository.selectList(new LambdaQueryWrapper<ContentLicense>()
                .eq(vendorOrgId != null, ContentLicense::getVendorOrgId, vendorOrgId)
                .orderByDesc(ContentLicense::getId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (ContentLicense lic : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", lic.getId());
            m.put("status", lic.getStatus());
            m.put("licensedAt", lic.getLicensedAt());
            if (ContentLicense.CONTENT_TYPE_AI_CREATION.equals(lic.getContentType()) && lic.getCreationId() != null) {
                AiCreation c = creationRepository.selectById(lic.getCreationId());
                m.put("recipeGroup", c == null ? ("AI创作#" + lic.getCreationId()) : ("AI创作：" + c.getTitle()));
                m.put("isAiCreation", true);
            } else {
                m.put("recipeGroup", lic.getRecipeGroup());
                m.put("isAiCreation", false);
            }
            out.add(m);
        }
        return out;
    }

    // ---------------- 删除（Admin 治理） ----------------

    @Transactional
    public boolean deleteContract(Long id) {
        if (contractRepository.selectById(id) == null) throw new IllegalArgumentException("合同不存在");
        return contractRepository.deleteById(id) > 0;
    }

    /** 删除 SKU（连同其全部绑定码，防孤儿） */
    @Transactional
    public boolean deleteSku(Long id) {
        DeviceSku sku = skuRepository.selectById(id);
        if (sku == null) throw new IllegalArgumentException("SKU 不存在");
        bindingRepository.delete(new LambdaQueryWrapper<DeviceBinding>().eq(DeviceBinding::getSkuId, id));
        return skuRepository.deleteById(id) > 0;
    }

    @Transactional
    public boolean deleteLicense(Long id) {
        if (licenseRepository.selectById(id) == null) throw new IllegalArgumentException("内容授权不存在");
        return licenseRepository.deleteById(id) > 0;
    }

    /** 删除绑定码（仅未激活；已激活需先解绑） */
    @Transactional
    public boolean deleteBinding(Long id) {
        DeviceBinding b = bindingRepository.selectById(id);
        if (b == null) throw new IllegalArgumentException("绑定码不存在");
        if (DeviceBinding.STATUS_BOUND.equals(b.getStatus())) {
            throw new IllegalArgumentException("已激活的绑定码不可删除，请先解绑");
        }
        return bindingRepository.deleteById(id) > 0;
    }

    // ---------------- AI 创作单品授权（B 端内容库复用，P4 延后项落地） ----------------

    /** 平台/厂商把 AI 创作授权给 B 端组织（content_type='ai_creation'），授权后进 B 端内容库 */
    @Transactional
    public Map<String, Object> licenseAiCreation(Long vendorOrgId, Long creationId) {
        requireOrg(vendorOrgId);
        if (creationId == null) throw new IllegalArgumentException("AI 创作 id 不能为空");
        AiCreation c = creationRepository.selectById(creationId);
        if (c == null) throw new IllegalArgumentException("AI 创作不存在");
        ContentLicense existing = licenseRepository.findByOrgAndCreation(vendorOrgId, creationId);
        if (existing != null) {
            existing.setStatus(ContentLicense.STATUS_ACTIVE);
            licenseRepository.updateById(existing);
        } else {
            ContentLicense lic = ContentLicense.builder()
                    .vendorOrgId(vendorOrgId)
                    .contentType(ContentLicense.CONTENT_TYPE_AI_CREATION)
                    .creationId(creationId)
                    .status(ContentLicense.STATUS_ACTIVE)
                    .licensedAt(LocalDateTime.now())
                    .build();
            licenseRepository.insert(lic);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("creationId", creationId);
        m.put("title", c.getTitle());
        m.put("status", ContentLicense.STATUS_ACTIVE);
        return m;
    }

    /** B 端内容库：本组织已授权的 AI 创作列表（含步骤数/视频就绪状态） */
    public List<Map<String, Object>> listLicensedAiCreations(Long vendorOrgId) {
        requireOrg(vendorOrgId);
        List<ContentLicense> licList = licenseRepository.selectList(new LambdaQueryWrapper<ContentLicense>()
                .eq(ContentLicense::getVendorOrgId, vendorOrgId)
                .eq(ContentLicense::getContentType, ContentLicense.CONTENT_TYPE_AI_CREATION)
                .eq(ContentLicense::getStatus, ContentLicense.STATUS_ACTIVE)
                .orderByDesc(ContentLicense::getId));
        List<Map<String, Object>> items = new ArrayList<>();
        for (ContentLicense lic : licList) {
            if (lic.getCreationId() == null) continue;
            AiCreation c = creationRepository.selectById(lic.getCreationId());
            if (c == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("creationId", c.getId());
            m.put("title", c.getTitle());
            m.put("summary", c.getSummary());
            m.put("tasteTags", CreationJsonUtil.parseStringArray(c.getTasteTags()));
            m.put("mainIngredients", CreationJsonUtil.parseStringArray(c.getMainIngredients()));
            m.put("estimatedTime", c.getEstimatedTime());
            m.put("difficulty", c.getDifficulty());
            m.put("spiceLevel", c.getSpiceLevel());
            m.put("stepCount", CreationJsonUtil.parseSteps(c.getSteps()).size());
            AiCreationVideo v = creationVideoRepository.findByCreationId(c.getId());
            m.put("videoReady", v != null && AiCreationVideo.STATUS_READY.equals(v.getStatus()));
            m.put("licensedAt", lic.getLicensedAt());
            items.add(m);
        }
        return items;
    }

    /** 撤销 AI 创作授权（置 disabled） */
    @Transactional
    public void revokeAiCreationLicense(Long vendorOrgId, Long creationId) {
        ContentLicense lic = licenseRepository.findByOrgAndCreation(vendorOrgId, creationId);
        if (lic == null) throw new IllegalArgumentException("该 AI 创作未授权给本组织");
        lic.setStatus("disabled");
        licenseRepository.updateById(lic);
    }

    // ---------------- C 端扫码激活 ----------------

    /** C 端用户扫码/输码激活：校验绑定码 + 合同有效 + 设备配额 → 绑定到用户 */
    @Transactional
    public Map<String, Object> activate(Long userId, String bindCode) {
        if (bindCode == null || bindCode.isBlank()) throw new IllegalArgumentException("请输入激活码");
        DeviceBinding b = bindingRepository.findByCode(bindCode.trim());
        if (b == null) throw new IllegalArgumentException("激活码不存在");
        if (!DeviceBinding.STATUS_UNBOUND.equals(b.getStatus())) {
            throw new IllegalArgumentException("该激活码已被使用");
        }
        VendorContract contract = contractRepository.findActiveByOrg(b.getVendorOrgId());
        if (contract == null) throw new IllegalArgumentException("该厂商暂无有效合作合同");
        long boundCount = bindingRepository.countBoundByOrg(b.getVendorOrgId());
        if (contract.getDeviceQuota() != null && boundCount >= contract.getDeviceQuota()) {
            throw new IllegalArgumentException("厂商设备激活额度已用尽");
        }
        b.setUserId(userId);
        b.setStatus(DeviceBinding.STATUS_BOUND);
        b.setBoundAt(LocalDateTime.now());
        bindingRepository.updateById(b);

        DeviceSku sku = skuRepository.selectById(b.getSkuId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bindingId", b.getId());
        m.put("bindCode", b.getBindCode());
        m.put("vendorOrgId", b.getVendorOrgId());
        m.put("deviceType", sku == null ? null : sku.getDeviceType());
        m.put("sku", sku == null ? null : sku.getSku());
        m.put("message", "设备绑定成功");
        log.info("device activated code={} user={} vendor={}", b.getBindCode(), userId, b.getVendorOrgId());
        return m;
    }

    private Org requireOrg(Long orgId) {
        Org org = orgRepository.selectById(orgId);
        if (org == null) throw new IllegalArgumentException("企业不存在");
        return org;
    }

    private VendorContract requireContract(Long contractId) {
        VendorContract c = contractRepository.selectById(contractId);
        if (c == null) throw new IllegalArgumentException("合同不存在");
        return c;
    }
}
