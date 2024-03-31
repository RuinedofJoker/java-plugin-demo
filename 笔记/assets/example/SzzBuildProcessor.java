package org.joker.example;

import javax.annotation.processing.*;

import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ExecutableType;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;
import java.util.stream.Collectors;

@SupportedAnnotationTypes("org.example.BuildProperty") // 只处理这个注解；
public class SzzBuildProcessor extends AbstractProcessor {

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        System.out.println("SzzBuildProcessor.process ;");

        for (TypeElement annotation : annotations) {
            // 获取所有被该注解 标记过的实例
            Set<? extends Element> annotatedElements = roundEnv.getElementsAnnotatedWith(annotation);

            // 按照需求 检查注解使用的是否正确 以set开头，并且参数只有一个
            Map<Boolean, List<Element>> annotatedMethods = annotatedElements.stream().collect(
                    Collectors.partitioningBy(element ->
                            ((ExecutableType) element.asType()).getParameterTypes().size() == 1
                                    && element.getSimpleName().toString().startsWith("set")));

            List<Element> setters = annotatedMethods.get(true);
            List<Element> otherMethods = annotatedMethods.get(false);

            // 打印注解使用错误的case
            otherMethods.forEach(element ->
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "@BuilderProperty 注解必须放到方法上并且是set开头的单参数方法", element));

            if (setters.isEmpty()) {
                continue;
            }


            Map<String, List<Element>> groupMap = new HashMap();

            // 按照全限定类名分组。一个类创建一个Build
            setters.forEach(setter -> {
                // 全限定类名
                String className = ((TypeElement) setter
                        .getEnclosingElement()).getQualifiedName().toString();
                List<Element> elements = groupMap.get(className);
                if (elements != null) {
                    elements.add(setter);
                } else {
                    List<Element> newElements = new ArrayList<>();
                    newElements.add(setter);
                    groupMap.put(className, newElements);
                }
            });


            groupMap.forEach((groupSetterKey, groupSettervalue) -> {
                //获取 类名SimpleName 和 set方法的入参
                Map<String, String> setterMap = groupSettervalue.stream().collect(Collectors.toMap(
                        setter -> setter.getSimpleName().toString(),
                        setter -> ((ExecutableType) setter.asType())
                                .getParameterTypes().get(0).toString()
                ));
                try {
                    // 组装XXXBuild类。并创建对应的类文件
                    writeBuilderFile(groupSetterKey, setterMap);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

            });
        }

        // 返回false 表示 当前处理器处理了之后 其他的处理器也可以接着处理，返回true表示，我处理完了之后其他处理器不再处理
        return true;
    }

    private void writeBuilderFile(
            String className, Map<String, String> setterMap)
            throws IOException {

        String packageName = null;
        int lastDot = className.lastIndexOf('.');
        if (lastDot > 0) {
            packageName = className.substring(0, lastDot);
        }

        String simpleClassName = className.substring(lastDot + 1);
        String builderClassName = className + "Builder";
        String builderSimpleClassName = builderClassName
                .substring(lastDot + 1);

        JavaFileObject builderFile = processingEnv.getFiler()
                .createSourceFile(builderClassName);

        try (PrintWriter out = new PrintWriter(builderFile.openWriter())) {

            if (packageName != null) {
                out.print("package ");
                out.print(packageName);
                out.println(";");
                out.println();
            }

            out.print("public class ");
            out.print(builderSimpleClassName);
            out.println(" {");
            out.println();

            out.print("    private ");
            out.print(simpleClassName);
            out.print(" object = new ");
            out.print(simpleClassName);
            out.println("();");
            out.println();

            out.print("    public ");
            out.print(simpleClassName);
            out.println(" build() {");
            out.println("        return object;");
            out.println("    }");
            out.println();

            setterMap.entrySet().forEach(setter -> {
                String methodName = setter.getKey();
                String argumentType = setter.getValue();

                out.print("    public ");
                out.print(builderSimpleClassName);
                out.print(" ");
                out.print(methodName);

                out.print("(");

                out.print(argumentType);
                out.println(" value) {");
                out.print("        object.");
                out.print(methodName);
                out.println("(value);");
                out.println("        return this;");
                out.println("    }");
                out.println();
            });

            out.println("}");
        }
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        System.out.println("----------");

        System.out.println(processingEnv.getOptions());

    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }


}
