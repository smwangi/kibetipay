/**
 * Shared kernel: money value type, error model, security and web plumbing.
 * Open module, so every other module may use all of its types. Keep it small:
 * anything business-specific belongs in its own module.
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN, displayName = "Shared Kernel")
package com.samwan.kibetipay.shared;

import org.springframework.modulith.ApplicationModule;
