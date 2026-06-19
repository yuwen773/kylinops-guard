package com.kylinops.inspection.model;

/**
 * 巡检内置模板类型。固定枚举,禁止扩展;模板契约在 InspectionTemplateRegistry(Task 3)静态定义。
 */
public enum InspectionTemplateType {
    HEALTH,
    DISK,
    SERVICE
}