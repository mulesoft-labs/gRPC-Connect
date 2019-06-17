package com.mulesoft.connectors.grpc;

import static com.mulesoft.connectors.grpc.ExtensionClassName.INJECT_ANNOTATION;
import static com.mulesoft.connectors.grpc.ExtensionClassName.LISTENABLE_FUTURE;
import static com.mulesoft.connectors.grpc.ExtensionClassName.MANAGED_CHANNEL;
import static com.mulesoft.connectors.grpc.ExtensionClassName.MANAGED_CHANNEL_BUILDER;
import static com.mulesoft.connectors.grpc.ExtensionClassName.RESULT;
import static com.mulesoft.connectors.grpc.ExtensionClassName.SCHEDULER;
import static com.mulesoft.connectors.grpc.ExtensionClassName.SCHEDULER_SERVICE;
import static javax.lang.model.element.Modifier.PUBLIC;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import javax.lang.model.element.Modifier;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeSpec.Builder;

public class Generator {

    static Map<String, TypeSpec> types = new HashMap<>();

    public static void main(String [] args) throws IOException {
        process(new File("/Users/ewasinger/Documents/mulesoft/experiments/gRPC-connect/target/generated-sources"), Thread.currentThread().getContextClassLoader().getResourceAsStream("descriptor.desc"));
    }

    public static void process(File outputFolder, InputStream descriptorFile) throws IOException {
        DescriptorProtos.FileDescriptorSet descriptorSet = DescriptorProtos.FileDescriptorSet.parseFrom(descriptorFile);
        FileDescriptorProto file = descriptorSet.getFile(0);

        for (DescriptorProto descriptorProto : file.getMessageTypeList()) {
            createMethod(descriptorProto, outputFolder);
        }

        ExtensionBuilder extensionBuilder = new ExtensionBuilder("SomeName", "Esteban Wasinger", Category.SELECT, "com.github.estebanwasinger");

        TypeSpec connection = createConnection("SomeConnection", file);

        createConnectionProvider(extensionBuilder);
        createOperationsContainer(extensionBuilder, file);

        TypeSpec build = extensionBuilder.build();

        extensionBuilder.withAdditionalClass(JavaType.create(connection, "com.github.estebanwasinger.internal"));

        extensionBuilder.writeJavaFiles(outputFolder);
    }

    private static TypeSpec createConnection(String className, FileDescriptorProto file) {
        Builder builder = TypeSpec.classBuilder(className);
        builder.addModifiers(PUBLIC);
        String classPackage = "com.github.estebanwasinger";
        ServiceDescriptorProto service = file.getService(0);
        String name = service.getName();
        ClassName futureStub = ClassName.get(classPackage, name + "Grpc." + name + "FutureStub");
        ClassName grpc = ClassName.get(classPackage, name + "Grpc");

        FieldSpec channel = FieldSpec.builder(MANAGED_CHANNEL, "channel", Modifier.PRIVATE, Modifier.FINAL).build();
        FieldSpec serviceFutureStub = FieldSpec.builder(futureStub, "serviceFutureStub", Modifier.PRIVATE, Modifier.FINAL).build();
        FieldSpec scheduler = FieldSpec.builder(SCHEDULER, "scheduler", Modifier.PRIVATE, Modifier.FINAL).build();

        builder.addField(channel);
        builder.addField(serviceFutureStub);
        builder.addField(scheduler);

        builder.addMethod(MethodSpec
                .constructorBuilder()
                .addModifiers(PUBLIC)
                .addParameter(String.class, "host")
                .addParameter(int.class, "port")
                .addParameter(SCHEDULER, "scheduler")
                .addStatement("this($T.forAddress(host, port)" +
                        ".usePlaintext()" +
                        ".build(), scheduler)", MANAGED_CHANNEL_BUILDER)
                .build());

        builder.addMethod(MethodSpec
                .constructorBuilder()
                .addModifiers(PUBLIC)
                .addParameter(MANAGED_CHANNEL, "channel")
                .addParameter(SCHEDULER, "scheduler")
                .addStatement("this.channel = channel")
                .addStatement("this.serviceFutureStub = $T.newFutureStub(channel)", grpc)
                .addStatement("this.scheduler = scheduler")
                .build());

        for (MethodDescriptorProto methodDescriptorProto : service.getMethodList()) {
            String methodName = methodName(methodDescriptorProto);
            MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(methodName);
            methodBuilder.addModifiers(PUBLIC);
            methodBuilder.returns(void.class);

            String[] split = methodDescriptorProto.getInputType().split("\\.");
            ClassName inputType = ClassName.get("com.github.estebanwasinger", types.get(split[split.length - 1]).name);
            ClassName inputApiType = ClassName.get("com.github.estebanwasinger.api", types.get(split[split.length - 1]).name);


            split = methodDescriptorProto.getOutputType().split("\\.");
            ClassName outputType = ClassName.get("com.github.estebanwasinger", types.get(split[split.length - 1]).name);
            ClassName outputApiType = ClassName.get("com.github.estebanwasinger.api", types.get(split[split.length - 1]).name);


            methodBuilder.addParameter(inputType, "input");

            methodBuilder.addParameter(ParameterSpec.builder(ExtensionClassName.COMPLETION_CALLBACK(outputApiType, ClassName.get(Void.class)), "completionCallback").build());

            methodBuilder.addStatement("$T<$T> listenableFuture = serviceFutureStub.$L(input);", LISTENABLE_FUTURE,outputType, methodName);
            methodBuilder.addStatement("listenableFuture.addListener(() -> {\n" +
                    "      try {\n" +
                    "        completionCallback.success($T.<$T, Void>builder().output($T.from(listenableFuture.get())).build());\n" +
                    "      } catch ($T | $T e) {\n" +
                    "        completionCallback.error(e);\n" +
                    "      }\n" +
                    "    }, scheduler);", RESULT, outputApiType, outputApiType, InterruptedException.class, ExecutionException.class);

            builder.addMethod(methodBuilder.build());
        }


        return builder.build();
    }

    private static void createOperationsContainer(ExtensionBuilder extensionBuilder, FileDescriptorProto descriptorProto) {
        OperationContainerBuilder operationContainerBuilder = extensionBuilder.withOperationContainer();

        for (ServiceDescriptorProto serviceDescriptorProto : descriptorProto.getServiceList()) {
            for (MethodDescriptorProto methodDescriptorProto : serviceDescriptorProto.getMethodList()) {
                OperationBuilder operationBuilder = operationContainerBuilder.withOperation(methodName(methodDescriptorProto));
                operationBuilder.withConnection("connection");

                operationBuilder.returns(TypeName.VOID);
                String[] split = methodDescriptorProto.getInputType().split("\\.");
                String paramName = split[split.length - 1].toLowerCase();
                operationBuilder.withParameter(paramName, ClassName.get("com.github.estebanwasinger.api", types.get(split[split.length - 1]).name));

                split = methodDescriptorProto.getOutputType().split("\\.");
                ClassName param1 = ClassName.get("com.github.estebanwasinger.api", types.get(split[split.length - 1]).name);
                operationBuilder.withParameter("completionCallback", ExtensionClassName.COMPLETION_CALLBACK(param1, ClassName.get(Void.class)));
                operationBuilder.withStatement(CodeBlock.of("connection.$L($L.to(),$L)",methodName(methodDescriptorProto), paramName, "completionCallback"));
            }
        }

        TypeSpec build = operationContainerBuilder.build();

        JavaFile javaFile = JavaFile.builder("com.github.estebanwasinger.api", build)
                .build();

        try {
            javaFile.writeTo(System.out);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static String methodName(MethodDescriptorProto methodDescriptorProto) {
        String name = methodDescriptorProto.getName();
        return name.substring(0,1).toLowerCase() + name.substring(1);
    }

    private static void createConnectionProvider(ExtensionBuilder extensionBuilder) {
        ClassName connectionType = ClassName.get(extensionBuilder.getPackage(), "SomeConnection");
        ConnectionProviderBuilder connectionProviderBuilder = extensionBuilder.withConnectionProvider("SomeName", connectionType);

        connectionProviderBuilder.asCached();
        connectionProviderBuilder.withField(FieldSpec.builder(SCHEDULER_SERVICE, "schedulerService").addAnnotation(INJECT_ANNOTATION).build());
        FieldSpec scheduler = FieldSpec.builder(SCHEDULER, "scheduler").build();
        connectionProviderBuilder.withField(scheduler);
        connectionProviderBuilder.withParameter("host", ClassName.get(String.class));
        connectionProviderBuilder.withParameter("port", int.class);
        connectionProviderBuilder.withConnectMethod(CodeBlock.builder().addStatement("return new $T($L,$L,$L)", connectionType, "host", "port", "schedulerService.ioScheduler()").build());

        TypeSpec build = connectionProviderBuilder.build();

        JavaFile javaFile = JavaFile.builder("com.github.estebanwasinger.api", build)
                .build();

        try {
            javaFile.writeTo(System.out);
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private static void createMethod(DescriptorProto descriptorProto, File outputFolder) throws IOException {
        Builder builder = TypeSpec.classBuilder(descriptorProto.getName())
                .addModifiers(PUBLIC);

        parseFields(descriptorProto, builder);

        addTransformers(descriptorProto, builder);

        TypeSpec build = builder.build();

        types.put(build.name, build);

        JavaFile javaFile = JavaFile.builder("com.github.estebanwasinger.api", build)
                .build();

        javaFile.writeTo(outputFolder);

    }

    private static void addTransformers(DescriptorProto descriptorProto, Builder builder) {

        String packageName = "com.github.estebanwasinger";
        String packageNameApi = "com.github.estebanwasinger.api";
        ClassName className = ClassName.get(packageName, descriptorProto.getName());
        ClassName returnType = ClassName.get(packageNameApi, descriptorProto.getName());
        addFrom(descriptorProto, builder, className, returnType);
        addTo(descriptorProto, builder, returnType, className);

    }

    private static void addTo(DescriptorProto descriptorProto, Builder builder, ClassName className, ClassName returnType) {
        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder("to")
                .addModifiers(PUBLIC)
                .returns(returnType);

        methodBuilder.addStatement("$T.Builder builder = $T.newBuilder()", returnType, returnType);
        for (FieldDescriptorProto field : descriptorProto.getFieldList()) {
            methodBuilder.addStatement("builder.$L(this.$L())", setterName(field.getName()), getterName(field.getName()));
        }
        methodBuilder.addStatement("return builder.build()");


        builder.addMethod(methodBuilder.build());
    }

    private static void addFrom(DescriptorProto descriptorProto, Builder builder, ClassName className, ClassName returnType) {
        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder("from")
                .addModifiers(PUBLIC, Modifier.STATIC)
                .addParameter(className, "param")
                .returns(returnType);

        methodBuilder.addStatement("$T type = new $T()", returnType, returnType);
        for (FieldDescriptorProto field : descriptorProto.getFieldList()) {
            methodBuilder.addStatement("type.$L(param.$L())", setterName(field.getName()), getterName(field.getName()));
        }
        methodBuilder.addStatement("return type");


        builder.addMethod(methodBuilder.build());
    }

    private static void parseFields(DescriptorProto descriptorProto, Builder builder) {
        for (FieldDescriptorProto fieldDescriptorProto : descriptorProto.getFieldList()) {
            parseField(builder, fieldDescriptorProto);
        }
    }

    private static void parseField(Builder builder, FieldDescriptorProto fieldDescriptorProto) {
        switch (fieldDescriptorProto.getType()) {
            case TYPE_STRING:
            {
                createProperty(builder, fieldDescriptorProto, String.class);
                break;
            }
            case TYPE_BOOL:
            {
                createProperty(builder, fieldDescriptorProto, boolean.class);
                break;
            }
            case TYPE_INT32:
            case TYPE_INT64:
            {
                createProperty(builder, fieldDescriptorProto, int.class);
                break;
            }
            case TYPE_MESSAGE:
            {
                System.out.println("TODO");
                break;
//                    FieldSpec build = FieldSpec.builder(TypeName.)
            }
            default:
                throw new RuntimeException(fieldDescriptorProto.getType() + "");
        }
    }

    private static void createProperty(Builder builder, FieldDescriptorProto fieldDescriptorProto, Class type) {
        String name = fieldDescriptorProto.getName();
        FieldSpec build = FieldSpec.builder(type, name).build();
        builder.addField(build);

        //TODO support for lists
//        if(fieldDescriptorProto.getLabel().equals(LABEL_REPEATED)){
//            type =
//        }

        builder.addMethod(MethodSpec
                .methodBuilder(setterName(name))
                .addModifiers(PUBLIC)
                .addParameter(type, name)
                .addStatement("this.$L = $L", name, name)
                .build());

        builder.addMethod(MethodSpec
                .methodBuilder(getterName(name))
                .addModifiers(PUBLIC)
                .returns(type)
                .addStatement("return this.$L", name)
                .build());
    }

    private static String getterName(String name) {
        return "get" + name.substring(0, 1).toUpperCase() + name.substring(1);
    }

    private static String setterName(String name) {
        return "set" + name.substring(0, 1).toUpperCase() + name.substring(1);
    }
}
