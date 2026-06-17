package com.example.demo.service;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 逆地理编码服务 —— 将经纬度坐标转换为人类可读的文字地址。
 *
 * <p>主要用于紧急求助短信：家人收到"浦东新区世纪大道附近"远比收到裸坐标更能快速定位盲人。
 * 实现必须保证紧急场景下的稳健性：超时 / API 失败 / 未配置 key 一律返回
 * {@link Optional#empty()}，由调用方降级处理，绝不阻塞紧急流程。
 */
public interface GeocodingService {

    /**
     * 逆地理编码：坐标 → 文字地址
     *
     * @param lat 纬度
     * @param lng 经度
     * @return 文字地址；无法解析时返回 {@link Optional#empty()}
     */
    Optional<String> reverseGeocode(BigDecimal lat, BigDecimal lng);
}
