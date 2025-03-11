plugins {
    id("java-library")
    alias(libs.plugins.jetbrains.kotlin.jvm)
    id("maven-publish")
    id("signing")
}
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}
dependencies {
    testImplementation(libs.junit)
}

publishing {
    repositories {
        maven {
            name = "MavenCentralRelease"
            credentials {
                username = properties["MAVEN_USERNAME"].toString()
                password = properties["MAVEN_PASSWORD"].toString()
            }
            url = uri(properties["RELEASE_REPOSITORY_URL"].toString())
        }
        maven {
            name = "MavenCentralSnapshot"
            credentials {
                username = properties["MAVEN_USERNAME"].toString()
                password = properties["MAVEN_PASSWORD"].toString()
            }
            url = uri(properties["SNAPSHOT_REPOSITORY_URL"].toString())
        }
        maven {
            name = "MavenLocal"
            url = uri(File(rootProject.projectDir, "maven"))
        }
    }

    publications {
        val defaultPublication = this.create("Default", MavenPublication::class.java)
        with(defaultPublication) {
            groupId = properties["GROUP_ID"].toString()
            artifactId = project.name
            version = properties["VERSION_NAME"].toString()

            afterEvaluate {
                artifact(tasks.getByName("jar"))
            }
            val sourceCode by tasks.creating(Jar::class.java) {
                archiveClassifier.set("sources")
                from(sourceSets.getByName("main").allSource)
            }
            val javaDoc by tasks.creating(Jar::class.java) {
                archiveClassifier.set("javadoc")
                from(tasks.javadoc.get().destinationDir)
            }
            artifact(sourceCode)
            artifact(javaDoc)
            pom {
                name = "tLruCache"
                description = "Disk and memory disk cache for Android and Jvm lib."
                url = "https://github.com/tans5/tLruCache.git"
                licenses {
                    license {
                        name = "The Apache License, Version 2.0"
                        url = "http://www.apache.org/licenses/LICENSE-2.0.txt"
                    }
                }
                developers {
                    developer {
                        id = "tanpengcheng"
                        name = "tans5"
                        email = "tans.tan096@gmail.com"
                    }
                }
                scm {
                    url.set("https://github.com/tans5/tLruCache.git")
                }
            }

            pom.withXml {
                val dependencies = asNode().appendNode("dependencies")
                configurations.implementation.get().allDependencies.all {
                    val dependency = this
                    if (dependency.group == null || dependency.version == null || dependency.name == "unspecified") {
                        return@all
                    }
                    val dependencyNode = dependencies.appendNode("dependency")
                    dependencyNode.appendNode("groupId", dependency.group)
                    dependencyNode.appendNode("artifactId", dependency.name)
                    dependencyNode.appendNode("version", dependency.version)
                    dependencyNode.appendNode("scope", "implementation")
                }
            }
        }
    }
}

//tasks.withType<Javadoc> {
//    options.addStringOption("Xdoclint:none", "-quiet")
//}

signing {
    sign(publishing.publications.getByName("Default"))
}
