package com.maixr.suggest;

import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * 让聊天里的物品名可以点击跳转到 JEI。
 *
 * API 链（全部 javap 实测确认，反射调用）：
 *   IJeiRuntime.getRecipesGui()                  → IRecipesGui
 *   IJeiRuntime.getIngredientManager()           → IIngredientManager
 *   IIngredientManager.createTypedIngredient(V)  → Optional<ITypedIngredient<V>>
 *   IJeiRuntime.getJeiHelpers().getFocusFactory()→ IFocusFactory
 *   IFocusFactory.createFocus(RecipeIngredientRole, ITypedIngredient) → IFocus
 *   IRecipesGui.show(IFocus)                     → 打开配方界面
 *
 * RecipeIngredientRole.OUTPUT = 合成表（按 R）；INPUT = 用途表（按 U）。
 */
public final class JeiLink {
    private static boolean resolved = false;
    private static boolean ok = false;

    private static Method rtGetRecipesGui, rtGetIngredientManager, rtGetHelpers;
    private static Method createTypedIngredient;      // (Object) -> Optional
    private static Method helpersGetFocusFactory;
    private static Method createFocus;                // (Role, ITypedIngredient) -> IFocus
    private static Method recipesGuiShow;             // (IFocus) -> void
    private static Object roleOutput;                 // RecipeIngredientRole.OUTPUT
    private static Object roleInput;                  // RecipeIngredientRole.INPUT（用途表，相当于按 U）

    private JeiLink() {}

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        try {
            Class<?> iRuntime = Class.forName("mezz.jei.api.runtime.IJeiRuntime");
            rtGetRecipesGui = iRuntime.getMethod("getRecipesGui");
            rtGetIngredientManager = iRuntime.getMethod("getIngredientManager");
            rtGetHelpers = iRuntime.getMethod("getJeiHelpers");
            Class<?> im = Class.forName("mezz.jei.api.runtime.IIngredientManager");
            createTypedIngredient = im.getMethod("createTypedIngredient", Object.class);
            Class<?> helpers = Class.forName("mezz.jei.api.helpers.IJeiHelpers");
            helpersGetFocusFactory = helpers.getMethod("getFocusFactory");
            Class<?> roleCls = Class.forName("mezz.jei.api.recipe.RecipeIngredientRole");
            for (Object o : roleCls.getEnumConstants()) {
                String n = String.valueOf(o);
                if ("OUTPUT".equals(n)) roleOutput = o;
                else if ("INPUT".equals(n)) roleInput = o;
            }
            Class<?> ff = Class.forName("mezz.jei.api.recipe.IFocusFactory");
            Class<?> ti = Class.forName("mezz.jei.api.ingredients.ITypedIngredient");
            createFocus = ff.getMethod("createFocus", roleCls, ti);
            Class<?> rg = Class.forName("mezz.jei.api.runtime.IRecipesGui");
            recipesGuiShow = rg.getMethod("show", Class.forName("mezz.jei.api.recipe.IFocus"));
            ok = roleOutput != null;
            MaixrSuggestMod.LOGGER.info("[猜你想搜] JEI 跳转链已就绪: {}", ok);
        } catch (Throwable t) {
            ok = false;
            MaixrSuggestMod.LOGGER.info("[猜你想搜] JEI 跳转链不可用: {}", t.toString());
        }
    }

    public static boolean isReady() { resolve(); return ok && JeiBridge.isReady(); }

    /** 打开该物品的 JEI 配方界面（合成表）。 */
    public static boolean showRecipes(ItemStack stack) { return show(stack, false); }

    /** 打开该物品的 JEI 界面；input = true 时打开"用途表"（相当于按 U）。 */
    public static boolean show(ItemStack stack, boolean input) {
        resolve();
        if (!ok || stack == null || stack.isEmpty()) return false;
        try {
            Object rt = rtOf();
            if (rt == null) return false;
            Object im = rtGetIngredientManager.invoke(rt);
            Object typedOpt = createTypedIngredient.invoke(im, stack);
            if (!(typedOpt instanceof Optional<?> o) || o.isEmpty()) return false;
            Object typed = o.get();
            Object helpers = rtGetHelpers.invoke(rt);
            Object ff = helpersGetFocusFactory.invoke(helpers);
            Object role = (input && roleInput != null) ? roleInput : roleOutput;
            Object focus = createFocus.invoke(ff, role, typed);
            Object gui = rtGetRecipesGui.invoke(rt);
            recipesGuiShow.invoke(gui, focus);
            return true;
        } catch (Throwable t) {
            MaixrSuggestMod.LOGGER.info("[猜你想搜] 跳转 JEI 失败: {}", t.toString());
            return false;
        }
    }

    /** 复用 JeiBridge 已解析的 runtime（它是私有方法，这里通过反射再取一次）。 */
    private static Object rtOf() {
        try {
            Class<?> internal = Class.forName("mezz.jei.common.Internal");
            Method m;
            try { m = internal.getMethod("getOptionalJeiRuntime"); }
            catch (NoSuchMethodException e) { m = internal.getMethod("getJeiRuntime"); }
            Object o = m.invoke(null);
            if (o instanceof Optional<?> opt) return opt.orElse(null);
            return o;
        } catch (Throwable t) { return null; }
    }
}
