plugins {
    id("jp.cobolinsight.java-conventions")
}

repositories {
    mavenLocal {
        content {
            includeGroup("org.eclipse.lsp.cobol")
        }
    }
}

dependencies {
    implementation(project(":engine:engine-api"))
    implementation("org.eclipse.lsp.cobol:engine:1.0.0-SNAPSHOT") {
        // 通常版 Guice 4.2.3 は JDK21 でクラス生成に失敗するため no_aop 版へ置き換える
        exclude(group = "com.google.inject", module = "guice")
    }
    implementation("com.google.inject:guice:4.2.3:no_aop")
}
