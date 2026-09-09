# V3 页面设计入口

涉及 V3 页面设计、UI 原型、视觉调整或新组件时，先读 [V3 设计哲学](docs/v3-design-philosophy.md) 的「三分钟接手」与对应组件章节，再开始实现。

- 用户确认的视觉基准是 [App 推介计划](mockup/referral-plan/index.html)。复用其主题染色、留白、字级、容器与动效关系；按业务调整内容。
- 新 Web 原型优先使用 [设计套件](mockup/v3-design-system/README.md) 中的 tokens、组件和 starter；通过 [组件图鉴](mockup/v3-design-system/index.html) 对照。
- 英文、数字与品牌文字使用 Montserrat 的真实 400–800 字重，中文保留系统中文字体回退；按规范同时设置字号、字重、行高与文字角色色。
- Android 继续复用 `witstudio`、`V3Palette` 和现有日夜资源。Web 色值与 px 不是 Android 全局资源的替换值；正文可读性和原生触控尺寸优先。
- 实现前明确推广/展示的对象、主操作及状态；示例业务规则不自动成为其他页面的需求。
- 验收覆盖深浅主题、窄屏、长文案、字体放大和主要交互状态。视觉规则的更新写回统一规范，避免只留在会话中。

这份文件仅约定 V3 设计工作，不改变其他任务的流程。
