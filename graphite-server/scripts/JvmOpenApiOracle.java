import com.google.gson.Gson;

/** Source-launch with the pinned main graphite-explore fat JAR on the class path. */
class JvmOpenApiOracle {
    public static void main(String[] args) throws Exception {
        Class<?> builder = Class.forName("io.johnsonlee.graphite.cli.OpenApiSpecBuilder");
        Object instance = builder.getDeclaredConstructor().newInstance();
        for (var method : builder.getDeclaredMethods()) {
            if ((method.getName().equals("build") || method.getName().startsWith("build$"))
                    && method.getParameterCount() == 0) {
                System.out.println(new Gson().toJson(method.invoke(instance)));
                return;
            }
        }
        throw new IllegalStateException("OpenApiSpecBuilder.build not found");
    }
}
