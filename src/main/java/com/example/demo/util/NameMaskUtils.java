package com.example.demo.util;

/**
 * 姓名脱敏工具类
 * 保留首字符，其余替换为 *
 * 例：张三 → 张*，欧阳娜娜 → 欧***
 */
public class NameMaskUtils {

    public static String mask(String name) {
        if (name == null || name.isEmpty()) return name;
        if (name.length() == 1) return "*";
        return name.charAt(0) + "*".repeat(name.length() - 1);
    }
}
