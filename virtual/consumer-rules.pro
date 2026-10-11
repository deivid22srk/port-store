# Regras consumer aplicadas ao :app quando :virtual é usado.
# O Bcore já embute regras próprias (top.niunaijun.blackbox.**, mirror.**);
# aqui apenas garantimos a interface interna e entidades expostas ao app.
-keep class com.deivid22srk.portstore.virtual.** { *; }
