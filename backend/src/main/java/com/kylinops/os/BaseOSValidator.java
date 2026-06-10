package com.kylinops.os;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;

/**
 * OS 工具公共校验工具类
 * <p>
 * 提供路径白名单校验、服务名正则校验、PID 正整数校验等静态方法，
 * 供所有 OS 感知 OpsTool 调用。
 * </p>
 */
public final class BaseOSValidator {

    /** 服务名正则：只允许字母、数字、. _ @ - */
    private static final Pattern SERVICE_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9._@-]+$");

    /** 大文件扫描允许的扫描根目录白名单 */
    public static final List<String> ALLOWED_SCAN_ROOTS = List.of(
            "/var/log", "/tmp", "/home"
    );

    private BaseOSValidator() {
        // 工具类，禁止实例化
    }

    /**
     * 校验并规范化路径。
     * <p>
     * 对输入路径调用 {@link Path#normalize()} 后检查是否以指定的白名单前缀开头。
     * 拒绝包含 ".." 穿越的路径。
     * </p>
     *
     * @param pathInput      原始路径输入
     * @param allowedPrefixes 允许的路径前缀列表
     * @return 规范化后的安全路径
     * @throws IllegalArgumentException 如果路径不合法或不在白名单内
     */
    public static String sanitizePath(String pathInput, List<String> allowedPrefixes) {
        if (pathInput == null || pathInput.isBlank()) {
            throw new IllegalArgumentException("路径不能为空");
        }

        String trimmed = pathInput.trim();

        // 拒绝空字符串或仅包含特殊字符的输入
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("路径不能为空字符串");
        }

        // 规范化前检查路径段，避免 ".." 在 normalize() 后被消除。
        Path rawPath;
        try {
            rawPath = Paths.get(trimmed);
        } catch (Exception e) {
            throw new IllegalArgumentException("非法路径格式: " + trimmed, e);
        }
        for (Path segment : rawPath) {
            if ("..".equals(segment.toString())) {
                throw new IllegalArgumentException("路径不能包含 '..' 穿越: " + trimmed);
            }
        }
        Path normalized = rawPath.normalize();

        String normalizedPath = normalized.toString();

        // 拒绝路径穿越（normalize 后仍含 ".." 表示尝试穿越）
        if (normalizedPath.contains("..")) {
            throw new IllegalArgumentException("路径不能包含 '..' 穿越: " + trimmed);
        }

        // 统一为前向斜杠（解决 Windows 路径分隔符问题）
        String unixPath = normalizedPath.replace('\\', '/');
        // 确保以 / 开头（Windows 上 Paths.get 可能去掉前导 /）
        if (!unixPath.startsWith("/")) {
            unixPath = "/" + unixPath;
        }

        // 检查是否在白名单前缀内
        boolean allowed = false;
        for (String prefix : allowedPrefixes) {
            if (unixPath.equals(prefix) || unixPath.startsWith(prefix + "/")) {
                allowed = true;
                break;
            }
        }

        if (!allowed) {
            throw new IllegalArgumentException("路径不在允许的扫描范围内，允许的根目录: " + allowedPrefixes);
        }

        return unixPath;
    }

    /**
     * 校验路径是否在扫描白名单内（仅检查，不返回规范化路径）。
     *
     * @param pathInput      原始路径输入
     * @param allowedPrefixes 允许的路径前缀列表
     * @return true 如果路径合法且在白名单内
     */
    public static boolean isValidPath(String pathInput, List<String> allowedPrefixes) {
        try {
            sanitizePath(pathInput, allowedPrefixes);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 校验服务名是否合法。
     * <p>
     * 合法字符: 字母、数字、点、下划线、@ 符号、连字符。
     * </p>
     *
     * @param name 服务名
     * @return true 如果服务名合法
     */
    public static boolean isValidServiceName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return SERVICE_NAME_PATTERN.matcher(name.trim()).matches();
    }

    /**
     * 校验 PID 是否为合法正整数。
     *
     * @param pidStr PID 字符串
     * @return true 如果 PID 是合法正整数（1 ≤ pid ≤ 2^22，典型最大值）
     */
    public static boolean isValidPid(String pidStr) {
        if (pidStr == null || pidStr.isBlank()) {
            return false;
        }
        try {
            int pid = Integer.parseInt(pidStr.trim());
            return pid > 0 && pid <= 4_194_304; // 2^22，远超实际最大 PID
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
