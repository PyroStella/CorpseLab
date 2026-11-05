按照下面的示例为项目添加geckolib mod前置，需要构建成功再交付，并且说明我需要在编译器怎么做
build.gradle
plugins {
id 'eclipse'
id 'idea'
id 'maven-publish'
id 'net.minecraftforge.gradle' version '[6.0,6.2)'
}

version = mod_version
group = mod_group_id

base {
archivesName = mod_id
}

// 1.20.1 对应 Java 17
java.toolchain.languageVersion = JavaLanguageVersion.of(17)

println "Java: ${System.getProperty 'java.version'}, JVM: ${System.getProperty 'java.vm.version'} (${System.getProperty 'java.vendor'}), Arch: ${System.getProperty 'os.arch'}"

minecraft {
mappings channel: mapping_channel, version: mapping_version
copyIdeResources = true

    // 第一人称布娃娃摄像机需要 Camera.setPosition，爆炸强度需要 Explosion.radius
    accessTransformer = file('src/main/resources/META-INF/accesstransformer.cfg')

    runs {
        configureEach {
            workingDirectory project.file('run')
            property 'forge.logging.markers', 'REGISTRIES'
            property 'forge.logging.console.level', 'debug'

            mods {
                "${mod_id}" {
                    source sourceSets.main
                }
            }
        }

        client {
            property 'forge.enabledGameTestNamespaces', mod_id
            // GeckoLib 带 mixin，开发环境要打开 refmap 重映射，否则 APPLY 阶段直接崩
            property 'mixin.env.remapRefMap', 'true'
            property 'mixin.env.refMapRemappingFile', "${projectDir}/build/createSrgToMcp/output.srg"
        }

        server {
            property 'forge.enabledGameTestNamespaces', mod_id
            args '--nogui'
        }

        data {
            workingDirectory project.file('run-data')
            args '--mod', mod_id, '--all', '--output', file('src/generated/resources/'), '--existing', file('src/main/resources/')
        }
    }
}

sourceSets.main.resources { srcDir 'src/generated/resources' }

repositories {
// GeckoLib 官方 maven，与防御塔 1.20.1 项目一致
maven {
name = 'GeckoLib'
url 'https://dl.cloudsmith.io/public/geckolib3/geckolib/maven/'
content {
includeGroupByRegex("software\\.bernie.*")
includeGroup("com.eliotlash.mclib")
}
}
}

dependencies {
minecraft "net.minecraftforge:forge:${minecraft_version}-${forge_version}"

    // geo 模型渲染用；物理内核本身不依赖它
    implementation fg.deobf("software.bernie.geckolib:geckolib-forge-${minecraft_version}:${geckolib_version}")
    implementation("com.eliotlash.mclib:mclib:20")

    // 1.20.1 自带 JOML，这里只是编译/测试期需要，不会打包
    implementation 'org.joml:joml:1.10.5'

    // 物理内核与 Minecraft 无关，测试可以脱离游戏跑
    testImplementation platform('org.junit:junit-bom:5.10.2')
    testImplementation 'org.junit.jupiter:junit-jupiter'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

// 物理内核零 Minecraft 依赖，单元测试跑得很快，改求解器前先跑这个
tasks.named('test', Test).configure {
useJUnitPlatform()
testLogging {
events 'passed', 'failed', 'skipped'
showStandardStreams = false
}
}

tasks.named('processResources', ProcessResources).configure {
var replaceProperties = [
minecraft_version: minecraft_version, minecraft_version_range: minecraft_version_range,
forge_version: forge_version, forge_version_range: forge_version_range,
loader_version_range: loader_version_range,
mod_id: mod_id, mod_name: mod_name, mod_license: mod_license, mod_version: mod_version,
mod_authors: mod_authors, mod_description: mod_description,
]
inputs.properties replaceProperties

    filesMatching(['META-INF/mods.toml', 'pack.mcmeta']) {
        expand replaceProperties + [project: project]
    }
}

tasks.named('jar', Jar).configure {
manifest {
attributes([
'Specification-Title'     : mod_id,
'Specification-Vendor'    : mod_authors,
'Specification-Version'   : '1',
'Implementation-Title'    : project.name,
'Implementation-Version'  : project.jar.archiveVersion,
'Implementation-Vendor'   : mod_authors,
'Implementation-Timestamp': new Date().format("yyyy-MM-dd'T'HH:mm:ssZ")
])
}

    finalizedBy 'reobfJar'
}

publishing {
publications {
register('mavenJava', MavenPublication) {
artifact jar
}
}
repositories {
maven {
url "file://${project.projectDir}/mcmodsrepo"
}
}
}

tasks.withType(JavaCompile).configureEach {
options.encoding = 'UTF-8'
}

gradle.properties
org.gradle.jvmargs=-Xmx8G
org.gradle.daemon=false

minecraft_version=1.20.1
minecraft_version_range=[1.20.1,1.21)
forge_version=47.4.10
forge_version_range=[47,)
loader_version_range=[47,)
mapping_channel=official
mapping_version=1.20.1

mod_id=corpselab
mod_name=Corpse Lab
mod_license=All Rights Reserved
mod_version=0.1.0
mod_group_id=com.pyrostella.corpselab
mod_authors=Pyrostella
mod_description=Experimental playground: server-authoritative ragdoll corpses that can be revived.

geckolib_version=4.8.4

settings.gradle
pluginManagement {
repositories {
gradlePluginPortal()
maven {
name = 'MinecraftForge'
url = 'https://maven.minecraftforge.net/'
}
}
}

plugins {
id 'org.gradle.toolchains.foojay-resolver-convention' version '0.7.0'
}

gradle-wrapper.properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-8.8-bin.zip
networkTimeout=120000
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists

