package com.zzq.gulimall.product.service.impl;

import com.alibaba.fastjson.TypeReference;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzq.common.constant.ProductConstant;
import com.zzq.common.to.SkuHasStockVo;
import com.zzq.common.to.SkuReductionTo;
import com.zzq.common.to.SpuBoundTo;
import com.zzq.common.to.es.SkuEsModel;
import com.zzq.common.utils.PageUtils;
import com.zzq.common.utils.Query;
import com.zzq.common.utils.R;
import com.zzq.gulimall.product.dao.SpuInfoDao;
import com.zzq.gulimall.product.entity.*;
import com.zzq.gulimall.product.feign.CouponFeignService;
import com.zzq.gulimall.product.feign.SearchFeignService;
import com.zzq.gulimall.product.feign.WareFeignService;
import com.zzq.gulimall.product.service.*;
import com.zzq.gulimall.product.vo.*;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


@Service("spuInfoService")
public class SpuInfoServiceImpl extends ServiceImpl<SpuInfoDao, SpuInfoEntity> implements SpuInfoService {


    @Autowired
    private SpuInfoDescService spuInfoDescService;

    @Autowired
    private SpuImagesService spuImagesService;

    @Autowired
    private AttrService attrService;

    @Autowired
    private ProductAttrValueService productAttrValueService;

    @Autowired
    private SkuInfoService skuInfoService;

    @Autowired
    private SkuImagesService skuImagesService;

    @Autowired
    private SkuSaleAttrValueService skuSaleAttrValueService;

    @Autowired
    private CouponFeignService couponFeignService;

    @Autowired
    private BrandService brandService;

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private WareFeignService wareFeignService;

    @Autowired
    private SearchFeignService searchFeignService;

    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        IPage<SpuInfoEntity> page = this.page(
                new Query<SpuInfoEntity>().getPage(params),
                new QueryWrapper<SpuInfoEntity>()
        );

        return new PageUtils(page);
    }

    @Transactional
    @Override
    public void saveSpuInfo(SpuSaveVo vo) {

        //1?????????spu???????????????pms_spu_info
        SpuInfoEntity spuInfoEntity = new SpuInfoEntity();
        BeanUtils.copyProperties(vo,spuInfoEntity);
        spuInfoEntity.setCreateTime(new Date());
        spuInfoEntity.setUpdateTime(new Date());
        this.saveBaseSpuInfo(spuInfoEntity);

        //2?????????spu??????????????????pms_spu_info_desc
        List<String> decript = vo.getDecript();
        SpuInfoDescEntity spuInfoDescEntity = new SpuInfoDescEntity();
        spuInfoDescEntity.setSpuId(spuInfoEntity.getId());
        spuInfoDescEntity.setDecript(String.join(",",decript));
        spuInfoDescService.saveSpuInfoDesc(spuInfoDescEntity);

        //3?????????spu???????????????pms_spu_images
        List<String> images = vo.getImages();
        spuImagesService.saveImages(spuInfoEntity.getId(),images);

        //4?????????spu??????????????????pms_product_attr_value
        List<BaseAttrs> baseAttrs = vo.getBaseAttrs();
        List<ProductAttrValueEntity> collect = baseAttrs.stream().map(attr -> {
            ProductAttrValueEntity valueEntity = new ProductAttrValueEntity();
            valueEntity.setAttrId(attr.getAttrId());

            //??????attr?????????
            AttrEntity byId = attrService.getById(attr.getAttrId());

            valueEntity.setAttrName(byId.getAttrName());
            valueEntity.setAttrValue(attr.getAttrValues());
            valueEntity.setQuickShow(attr.getShowDesc());
            valueEntity.setSpuId(spuInfoEntity.getId());
            return valueEntity;
        }).collect(Collectors.toList());
        productAttrValueService.saveProductAttr(collect);


        //5?????????spu??????????????????gulimall_sms--->sms_spu_bounds
        Bounds bounds = vo.getBounds();
        SpuBoundTo spuBoundTo = new SpuBoundTo();
        BeanUtils.copyProperties(bounds,spuBoundTo);
        spuBoundTo.setSpuId(spuInfoEntity.getId());
        R r = couponFeignService.saveSpuBounds(spuBoundTo);

        if (r.getCode() != 0) {
            log.error("????????????spu??????????????????");
        }

        //5???????????????spu???????????????sku?????????pms_sku_info
        //5???1??????sku???????????????:pms_sku_info
        List<Skus> skus = vo.getSkus();
        if(skus!=null && skus.size()>0){
            skus.forEach(item->{
                String defaultImg = "";
                for (Images image : item.getImages()) {
                    if(image.getDefaultImg() == 1){
                        defaultImg = image.getImgUrl();
                    }
                }

                SkuInfoEntity skuInfoEntity = new SkuInfoEntity();
                BeanUtils.copyProperties(item,skuInfoEntity);
                skuInfoEntity.setBrandId(spuInfoEntity.getBrandId());
                skuInfoEntity.setCatalogId(spuInfoEntity.getCatalogId());
                skuInfoEntity.setSaleCount(0L);
                skuInfoEntity.setSpuId(spuInfoEntity.getId());
                skuInfoEntity.setSkuDefaultImg(defaultImg);
                skuInfoService.saveSkuInfo(skuInfoEntity);

                Long skuId = skuInfoEntity.getSkuId();

                List<SkuImagesEntity> imagesEntities = item.getImages().stream().map(img -> {
                    SkuImagesEntity skuImagesEntity = new SkuImagesEntity();
                    skuImagesEntity.setSkuId(skuId);
                    skuImagesEntity.setImgUrl(img.getImgUrl());
                    skuImagesEntity.setDefaultImg(img.getDefaultImg());
                    return skuImagesEntity;
                }).filter(entity -> {
                    //??????true???????????????false????????????
                    return !StringUtils.isEmpty(entity.getImgUrl());
                }).collect(Collectors.toList());

                //5???2??????sku??????????????????pms_sku_images
                skuImagesService.saveBatch(imagesEntities);

                //5???3??????sku??????????????????pms_sku_sale_attr_value
                List<Attr> attr = item.getAttr();
                List<SkuSaleAttrValueEntity> skuSaleAttrValueEntities = attr.stream().map(a -> {
                    SkuSaleAttrValueEntity skuSaleAttrValueEntity = new SkuSaleAttrValueEntity();
                    BeanUtils.copyProperties(a, skuSaleAttrValueEntity);
                    skuSaleAttrValueEntity.setSkuId(skuId);
                    return skuSaleAttrValueEntity;
                }).collect(Collectors.toList());

                skuSaleAttrValueService.saveBatch(skuSaleAttrValueEntities);

                //5???4??????sku??????????????????????????????gulimall_sms--->sms_sku_ladder???sms_sku_full_reduction???sms_member_price
                SkuReductionTo skuReductionTo = new SkuReductionTo();
                BeanUtils.copyProperties(item,skuReductionTo);
                skuReductionTo.setSkuId(skuId);
                if (skuReductionTo.getFullCount() > 0 || skuReductionTo.getFullPrice().compareTo(BigDecimal.ZERO) == 1) {
                    R r1 = couponFeignService.saveSkuReduction(skuReductionTo);
                    if (r1.getCode() != 0) {
                        log.error("????????????sku??????????????????");
                    }
                }
            });
        }

    }

    @Override
    public PageUtils queryPageByCondition(Map<String, Object> params) {

        QueryWrapper<SpuInfoEntity> queryWrapper = new QueryWrapper<>();

        String key = (String) params.get("key");
        if (!StringUtils.isEmpty(key)) {
            queryWrapper.and((wrapper) -> {
                wrapper.eq("id",key).or().like("spu_name",key);
            });
        }

        String status = (String) params.get("status");
        if (!StringUtils.isEmpty(status)) {
            queryWrapper.eq("publish_status",status);
        }

        String brandId = (String) params.get("brandId");
        if (!StringUtils.isEmpty(brandId) && !"0".equalsIgnoreCase(brandId)) {
            queryWrapper.eq("brand_id",brandId);
        }

        String catelogId = (String) params.get("catelogId");
        if (!StringUtils.isEmpty(catelogId) && !"0".equalsIgnoreCase(catelogId)) {
            queryWrapper.eq("catalog_id",catelogId);
        }

        IPage<SpuInfoEntity> page = this.page(new Query<SpuInfoEntity>().getPage(params), queryWrapper);

        return new PageUtils(page);

    }

    public void saveBaseSpuInfo(SpuInfoEntity spuInfoEntity) {

        this.baseMapper.insert(spuInfoEntity);

    }

    /**
     * 1???product ????????????  =>  ??? ES ??????????????? Entity
     * 2????????? search ?????????????????????
     * @param spuId
     */
    @Override
    public void up(Long spuId) {
        //1???????????????spuId???????????????sku??????,???????????????
        List<SkuInfoEntity> skuInfoEntities = skuInfoService.getSkusBySpuId(spuId);

        //TODO 4???????????????sku????????????????????????????????????????????? ???????????? ???pms_attr?????????????????????
        List<ProductAttrValueEntity> baseAttrs = productAttrValueService.baseAttrListforspu(spuId);
        //???????????? spuID ????????? pms_attr ????????? attrIds
        List<Long> attrIds = baseAttrs.stream().map(attr -> {
            return attr.getAttrId();
        }).collect(Collectors.toList());
        //where in(attrIds)
        List<Long> searchAttrIds = attrService.selectSearchAttrs(attrIds);
        //?????????Set?????? ?????????new set????????????
        Set<Long> idSet = searchAttrIds.stream().collect(Collectors.toSet());

        List<SkuEsModel.Attrs> attrsList = baseAttrs.stream().filter(item -> {
            return idSet.contains(item.getAttrId());
        }).map(item -> {
            SkuEsModel.Attrs attrs = new SkuEsModel.Attrs();
            BeanUtils.copyProperties(item, attrs);
            return attrs;
        }).collect(Collectors.toList());

        List<Long> skuIdList = skuInfoEntities.stream()
                .map(SkuInfoEntity::getSkuId)
                .collect(Collectors.toList());
        //TODO 1?????????????????????????????????????????????????????????
        Map<Long, Boolean> stockMap = null;
        try {
            R skuHasStock = wareFeignService.getSkuHasStock(skuIdList);
            //
            TypeReference<List<SkuHasStockVo>> typeReference = new TypeReference<List<SkuHasStockVo>>() {};
            stockMap = skuHasStock.getData(typeReference).stream()
                    .collect(Collectors.toMap(SkuHasStockVo::getSkuId, item -> item.getHasStock()));
        } catch (Exception e) {
            log.error("?????????????????????????????????{}",e);
        }

        //2???????????????sku?????????
        Map<Long, Boolean> finalStockMap = stockMap;
        List<SkuEsModel> collect = skuInfoEntities.stream().map(sku -> {
            //?????????????????????
            SkuEsModel esModel = new SkuEsModel();
            esModel.setSkuPrice(sku.getPrice());
            esModel.setSkuImg(sku.getSkuDefaultImg());

            //??????????????????
            if (finalStockMap == null) {
                esModel.setHasStock(true);
            } else {
                esModel.setHasStock(finalStockMap.get(sku.getSkuId()));
            }

            //TODO 2??????????????????0
            esModel.setHotScore(0L);

            //TODO 3???????????????????????????????????????
            BrandEntity brandEntity = brandService.getById(sku.getBrandId());
            esModel.setBrandName(brandEntity.getName());
            esModel.setBrandId(brandEntity.getBrandId());
            esModel.setBrandImg(brandEntity.getLogo());

            CategoryEntity categoryEntity = categoryService.getById(sku.getCatalogId());
            esModel.setCatalogId(categoryEntity.getCatId());
            esModel.setCatalogName(categoryEntity.getName());

            //??????????????????
            esModel.setAttrs(attrsList);

            BeanUtils.copyProperties(sku,esModel);

            return esModel;
        }).collect(Collectors.toList());

        //TODO 5??????????????????es???????????????gulimall-search
        R r = searchFeignService.productStatusUp(collect);

        if (r.getCode() == 0) {
            //??????????????????
            //TODO 6???????????????spu?????????
            this.baseMapper.updaSpuStatus(spuId, ProductConstant.ProductStatusEnum.SPU_UP.getCode());
        } else {
            //??????????????????
            //TODO 7?????????????????????????????????:????????????
        }
    }

    /**
     * ??????skuId??????spu?????????
     * @param skuId
     * @return
     */
    @Override
    public SpuInfoEntity getSpuInfoBySkuId(Long skuId) {

        //?????????sku???????????????
        SkuInfoEntity skuInfoEntity = skuInfoService.getById(skuId);

        //??????spuId
        Long spuId = skuInfoEntity.getSpuId();

        //?????????spuId??????spuInfo?????????????????????
        SpuInfoEntity spuInfoEntity = this.baseMapper.selectById(spuId);

        //???????????????????????????????????????
        BrandEntity brandEntity = brandService.getById(spuInfoEntity.getBrandId());
        spuInfoEntity.setBrandName(brandEntity.getName());

        return spuInfoEntity;
    }

}