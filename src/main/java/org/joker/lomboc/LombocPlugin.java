package org.joker.lomboc;

import com.google.auto.service.AutoService;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Names;
import org.joker.lomboc.annotation.Setter;

import javax.annotation.processing.*;

import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.Locale;
import java.util.Set;

@SupportedAnnotationTypes(value = {"org.joker.lomboc.annotation.Setter"})
@SupportedSourceVersion(SourceVersion.RELEASE_8)
@AutoService(Processor.class)
public class LombocPlugin extends AbstractProcessor {

    private JavacTrees trees;

    private TreeMaker treeMaker;

    private Names names;

    private Messager messager;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        trees = JavacTrees.instance(processingEnv);
        if (processingEnv instanceof JavacProcessingEnvironment) {
            final Context context = ((JavacProcessingEnvironment) processingEnv).getContext();
            treeMaker = TreeMaker.instance(context);
            names = Names.instance(context);
        }
        messager = processingEnv.getMessager();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        // 我们在这里的最终目的是获取到自定义注解所标记Element所在类的Element，然后获取该类Element的jctree对象
        // 使用treeMaker来为该类对象添加你自定义的JCTree节点

        // ------------------------获取元素所在类名

        for (Element element : roundEnv.getElementsAnnotatedWith(Setter.class)) {
            messager.printMessage(Diagnostic.Kind.NOTE, String.format("%s filed find a @Setter", element.getSimpleName().toString()));

            // 这是获取当前element元素的根节点，如果有添加导入包等的需求需要使用这个根节点
            CompilationUnitTree compilationUnitTree = trees.getPath(element).getCompilationUnit();
            JCTree.JCCompilationUnit jcCompilationUnit = (JCTree.JCCompilationUnit) compilationUnitTree;

            JCTree classTree;
            String filedName;
            if (element.getKind().equals(ElementKind.CLASS)) {
                // 如果注解标记的就是类
                classTree = trees.getTree(element);
                messager.printMessage(Diagnostic.Kind.ERROR, "add setter fail;Element is not a filed");
                return true;
            } else {
                // 如果注解标记的不是类
                Element enclosingElement = null;
                while (enclosingElement == null ||
                        (!enclosingElement.getKind().equals(ElementKind.CLASS))
                ) {
                    enclosingElement = element.getEnclosingElement();
                }
                classTree = trees.getTree(enclosingElement);
                filedName = element.getSimpleName().toString();
            }
            JCTree elementTree = trees.getTree(element);

            // ------------------------编写新的JCTree节点并将新加的JCTree节点添加到所在类里面
            // 导包
            //importPackage(jcCompilationUnit);
            // 添加成员变量
            //addVariable(classTree, element, elementTree);
            // 添加方法
            addMethod(classTree, element, elementTree);

            messager.printMessage(Diagnostic.Kind.NOTE, "add setter success");
        }
        return true;
    }

    // 导一个 com.google.gson.Gson 的包
    private void importPackage(JCTree.JCCompilationUnit jcCompilationUnit) {
        JCTree.JCImport jcImport = treeMaker.Import(
                treeMaker.Select(
                        treeMaker.Ident(names.fromString("com")),
                        names.fromString("google")
                ),
                false
        );

        jcImport = treeMaker.Import(
                treeMaker.Select(
                        (JCTree.JCExpression) jcImport.getQualifiedIdentifier(),
                        names.fromString("gson")
                ),
                jcImport.staticImport
        );

        jcImport = treeMaker.Import(
                treeMaker.Select(
                        (JCTree.JCExpression) jcImport.getQualifiedIdentifier(),
                        names.fromString("Gson")
                ),
                jcImport.staticImport
        );

        jcCompilationUnit.defs = jcCompilationUnit.defs.prepend(jcImport);
    }

    private void addVariable(JCTree jcTree, Element element, JCTree elementTree) {
        JCTree.JCClassDecl classDecl = (JCTree.JCClassDecl) jcTree;

        // private String name
        JCTree.JCVariableDecl jcVariableDecl = treeMaker.VarDef(
                treeMaker.Modifiers(Flags.PRIVATE),
                names.fromString(element.getSimpleName().toString()),
                treeMaker.Ident(names.fromString(((JCTree.JCVariableDecl) elementTree).vartype.toString())),
                null
        );

        classDecl.defs = classDecl.defs.append(jcVariableDecl);
    }

    private void addMethod(JCTree jcTree, Element element, JCTree elementTree) {
        String fieldName = element.getSimpleName().toString();

        // 入参 (String name)
        List<JCTree.JCVariableDecl> inputParam = List.of(treeMaker.VarDef(
                treeMaker.Modifiers(Flags.PARAMETER),
                names.fromString(fieldName),
                treeMaker.Ident(names.fromString(((JCTree.JCVariableDecl) elementTree).vartype.toString())),
                null
        ));

        ListBuffer<JCTree.JCStatement> methodBodyList = new ListBuffer<>();
        methodBodyList.append(
                // this.name = name
                treeMaker.Exec(treeMaker.Assign(
                        treeMaker.Select(treeMaker.Ident(names.fromString("this")), names.fromString(fieldName)),
                        treeMaker.Ident(names.fromString(fieldName))
                ))
        );

        JCTree.JCClassDecl classDecl = (JCTree.JCClassDecl) jcTree;
        JCTree.JCMethodDecl jcMethodDecl = treeMaker.MethodDef(
                treeMaker.Modifiers(Flags.PUBLIC),
                names.fromString("set" + fieldName.substring(0, 1).toUpperCase(Locale.ROOT) + fieldName.substring(1)),
                treeMaker.Type(new Type.JCVoidType()),
                List.nil(),
                inputParam,
                List.nil(),
                treeMaker.Block(0, methodBodyList.toList()),
                null
        );
        classDecl.defs = classDecl.defs.append(jcMethodDecl);
    }

    private void rewriteClass(String pkg, String className, String filedName) throws IOException {
        final JavaFileObject sourceFile = processingEnv.getFiler().createClassFile(pkg + ".New" + className);
        try (Writer writer = sourceFile.openWriter()) {
            writer.write("package " + pkg + ";");
            writer.write("class New" + className + " {");
            writer.write("private int " + filedName + ";");
            writer.write("}");
        }
    }
}
