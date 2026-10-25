# Building

You need JDK 21 or newer and Maven 3.9 or newer.

```bash
mvn -version
mvn clean package
```

The plugin lands at **`target/concierge.hpi`**.

> **If your `~/.m2/settings.xml` mirrors `external:*`**
>
> A private repository manager that does not proxy `repo.jenkins-ci.org` will break resolution of
> the Jenkins parent pom. This repository ships a minimal settings file for that case:
>
> ```bash
> mvn -s .mvn/settings.xml clean package
> ```

## The full gate

```bash
mvn clean install
```

This is what CI and ci.jenkins.io run. If Spotless complains, run `mvn spotless:apply`.

## Running Jenkins with the plugin

```bash
mvn hpi:run
```

This starts Jenkins at <http://localhost:8080/jenkins/> with the plugin installed.

## Installing a build into your own Jenkins

Upload `target/concierge.hpi` under **Manage Jenkins → Plugins → Advanced settings → Deploy
Plugin**. Install the **Credentials** and **Plain Credentials** plugins first; Concierge depends on
them.
