/*
 * Copyright 2026 Blocker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.merxury.blocker.feature.generalrule.impl

import com.merxury.blocker.core.model.data.GeneralRule

/** User-facing SDK families derived from existing rule metadata without changing the rule schema. */
enum class GeneralRuleCategory {
    ALL, ADVERTISING, ANALYTICS, PUSH, SOCIAL, AUTH, OTHER,
}

fun GeneralRule.category(): GeneralRuleCategory {
    val metadata = buildString {
        append(name); append(' '); append(company.orEmpty()); append(' ')
        append(description.orEmpty()); append(' '); append(searchKeyword.joinToString(" "))
    }.lowercase()
    return when {
        metadata.containsAny("admob", "advertis", "adservice", "adsdk", "ad sdk", ".ads.") -> GeneralRuleCategory.ADVERTISING
        metadata.containsAny("analytics", "statistic", "tracking", "telemetry", "sensorsdata", "mixpanel", "appsflyer", "flurry", "adjust", "applog") -> GeneralRuleCategory.ANALYTICS
        metadata.containsAny("push", "notification", "fcm", "jpush", "getui", "umeng") -> GeneralRuleCategory.PUSH
        metadata.containsAny("social", "share", "facebook", "twitter", "weibo", "wechat") -> GeneralRuleCategory.SOCIAL
        metadata.containsAny("login", "signin", "sign-in", "oauth", "auth", "account") -> GeneralRuleCategory.AUTH
        else -> GeneralRuleCategory.OTHER
    }
}

private fun String.containsAny(vararg values: String): Boolean = values.any(::contains)
