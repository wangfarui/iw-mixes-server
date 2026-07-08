package com.itwray.iw.external.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.itwray.iw.common.utils.DateUtils;
import com.itwray.iw.external.dao.ExternalExchangeRateDao;
import com.itwray.iw.external.model.dto.GetExchangeRateDto;
import com.itwray.iw.external.model.entity.ExternalExchangeRateEntity;
import com.itwray.iw.external.model.vo.GetExchangeRateVo;
import com.itwray.iw.external.service.InternalApiService;
import com.itwray.iw.web.constants.WebCommonConstants;
import com.itwray.iw.web.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * 内部接口服务实现层
 *
 * @author wray
 * @since 2025/4/12
 */
@Service
@Slf4j
public class InternalApiServiceImpl implements InternalApiService {

    @Autowired
    private ExternalExchangeRateDao externalExchangeRateDao;

    /**
     * 汇率api的accessKey
     */
    @Value("${iw.external.exchange-rate.key:${iw.external.exchangeRate.key:}}")
    private String exchangeRateAccessKey;

    @Override
    public GetExchangeRateVo getExchangeRate(GetExchangeRateDto dto) {
        if (dto.getQueryDate() == null || dto.getQueryDate().isAfter(LocalDate.now())) {
            dto.setQueryDate(LocalDate.now());
        }
        if (dto.getFromAmount() == null) {
            dto.setFromAmount(BigDecimal.ONE);
        }

        // 查询历史汇率
        ExternalExchangeRateEntity oldExchangeRateEntity = externalExchangeRateDao.lambdaQuery()
                .eq(ExternalExchangeRateEntity::getQueryDate, dto.getQueryDate())
                .eq(ExternalExchangeRateEntity::getFromCurrency, dto.getFromCurrency())
                .eq(ExternalExchangeRateEntity::getToCurrency, dto.getToCurrency())
                .last(WebCommonConstants.LIMIT_ONE)
                .one();
        if (oldExchangeRateEntity != null) {
            BigDecimal toAmount = oldExchangeRateEntity.getExchangeRate().multiply(dto.getFromAmount());
            GetExchangeRateVo vo = BeanUtil.copyProperties(dto, GetExchangeRateVo.class);
            vo.setToAmount(toAmount);
            vo.setExchangeRate(oldExchangeRateEntity.getExchangeRate());
            return vo;
        }

        // 在线查询汇率
        if (StrUtil.isBlank(exchangeRateAccessKey)) {
            throw new BusinessException("汇率服务未配置");
        }
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("from", dto.getFromCurrency());
        paramMap.put("to", dto.getToCurrency());
        paramMap.put("date", dto.getQueryDate().format(DateUtils.DATE_FORMATTER));
        paramMap.put("access_key", exchangeRateAccessKey);
        paramMap.put("amount", dto.getFromAmount());
        JSONObject jsonObject;
        try {
            String res = HttpUtil.get("https://api.exchangerate.host/convert", paramMap);
            Map<String, Object> logParamMap = new HashMap<>(paramMap);
            logParamMap.put("access_key", "***");
            log.info("InternalApiService#getExchangeRate paramMap: {}, res: {}", JSONUtil.toJsonStr(logParamMap), res);
            jsonObject = JSONUtil.parseObj(res);
        } catch (Exception e) {
            log.warn("InternalApiService#getExchangeRate request failed, from: {}, to: {}, date: {}",
                    dto.getFromCurrency(), dto.getToCurrency(), dto.getQueryDate(), e);
            throw new BusinessException("汇率查询失败，请稍后重试");
        }

        JSONObject info = jsonObject.getJSONObject("info");
        BigDecimal exchangeRate = info == null ? null : info.getBigDecimal("quote");
        BigDecimal toAmount = jsonObject.getBigDecimal("result");
        if (exchangeRate == null || toAmount == null) {
            log.warn("InternalApiService#getExchangeRate invalid response, from: {}, to: {}, date: {}, res: {}",
                    dto.getFromCurrency(), dto.getToCurrency(), dto.getQueryDate(), jsonObject);
            throw new BusinessException("汇率查询失败，请稍后重试");
        }

        // 保存查询后的汇率
        ExternalExchangeRateEntity entity = new ExternalExchangeRateEntity();
        entity.setFromCurrency(dto.getFromCurrency());
        entity.setToCurrency(dto.getToCurrency());
        entity.setExchangeRate(exchangeRate);
        entity.setQueryDate(dto.getQueryDate());
        entity.setFromAmount(dto.getFromAmount());
        entity.setToAmount(toAmount);
        externalExchangeRateDao.save(entity);

        return BeanUtil.copyProperties(entity, GetExchangeRateVo.class);
    }
}
