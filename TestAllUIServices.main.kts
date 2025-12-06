quiteimport sun.jvm.hotspot.debugger.Page

/**
     * Light "post-login" settle:
     * - Try to detect typical logged-in/app-shell elements.
     * - Fallback to a very short wait if nothing obvious appears.
     */
    fun waitForPostLoginContent(page: Page, serviceName: String) {
        val candidates = listOf(
            "text=Logout",
            "text=Log out",
            "text=Sign out",
            "a[href*='logout']",
            "button:has-text('Logout')",
            "button:has-text('Log out')",
            "nav",
            "main"
        )

        for (selector in candidates) {
            try {
                page.waitForSelector(
                    selector,
                    Page.WaitForSelectorOptions().setTimeout(1_500.0)
                )
                return
            } catch (_: Exception) {
                // Try next
            }
        }

        // If nothing matched, tiny fallback pause
        try {
            page.waitForTimeout(500.0)
        } catch (_: Exception) {
        }
    }

    /**
     * Retry helper.
     */
    fun withRetries(times: Int = 3, pauseMs: Double = 200.0, block: () -> Boolean): Boolean {
        repeat(times) { attempt ->
            if (block()) return true
            try { Thread.sleep(pauseMs.toLong()) } catch (_: Exception) {}
        }
        return false
    }

    /**
     * Read current value of an input.
     */
    fun getInputValue(locator: Locator): String? {
        return try {
            locator.inputValue()
        } catch (_: Exception) {
            try {
                locator.evaluate("el => el && 'value' in el ? el.value : null") as? String
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Robustly fill an input:
     * - Scrolls into view
     * - Focus/click
     * - Clears current value
     * - Types with small delay (to trigger listeners)
     * - Verifies value
     * - Falls back to JS set + input/change events if needed
     */
    fun robustFillInput(page: Page, locator: Locator, value: String, label: String = "field"): Boolean {
        return withRetries(times = 3) {
            try {
                try { locator.scrollIntoViewIfNeeded() } catch (_: Exception) {}
                try { locator.waitFor(Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(ELEMENT_WAIT_MS)) } catch (_: Exception) {}

                // Focus/click
                try { locator.click(Locator.ClickOptions().setTimeout(1_500.0)) } catch (_: Exception) {}

                // Clear current value
                try { locator.fill("") } catch (_: Exception) {}
                try {
                    locator.type(value, Locator.TypeOptions().setDelay(25.0))
                } catch (_: Exception) {
                    try { locator.fill(value) } catch (_: Exception) {}
                }

                // Verify and fallback if needed
                val current = getInputValue(locator)
                if (current == value) return@withRetries true

                // Fallback via JS direct set + events
                val escaped = value.replace("'", "\\'")
                try {
                    locator.evaluate(
                        "el => { " +
                                "el.value = '$escaped'; " +
                                "el.dispatchEvent(new Event('input', { bubbles: true })); " +
                                "el.dispatchEvent(new Event('change', { bubbles: true })); " +
                                "}"
                    )
                } catch (_: Exception) {
                }

                val after = getInputValue(locator)
                if (after == value) return@withRetries true

                false
            } catch (e: Exception) {
                println("  ⚠️  Failed to fill $label: ${e.message}")
                false
            }
        }.also { ok ->
            if (!ok) println("  ❌ Could not reliably fill $label")
        }
    }

    // ---- Authelia ----
