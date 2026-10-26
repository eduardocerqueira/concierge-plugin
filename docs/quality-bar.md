# Quality bar

> **Every pull request must have all tests passing, and zero known vulnerabilities in anything
> this plugin ships.**

Both are enforced by CI.

## The test gate

[`ci.yml`](../.github/workflows/ci.yml) runs `mvn clean install` on JDK 21 and 25, the same command
and JDKs as ci.jenkins.io. That includes the tests, SpotBugs, Spotless formatting and javadoc.

Every change that touches what a user can see needs a test proving a user without permission
cannot see it. That is the most important test in this plugin. See
[security-design.md](security-design.md).

## The CVE gate

[`cve-scan.yml`](../.github/workflows/cve-scan.yml) builds two SBOMs with CycloneDX and scans both
with Trivy:

- **Shipped**: compile and runtime scope, everything inside the `.hpi`. The bar is zero, and it
  blocks the merge.
- **Full tree**: also Jenkins core and the libraries it brings. A plugin ships none of that, so it is
  reported loudly in the job summary but does not block. It is cleared by moving `jenkins.baseline`
  forward.

The repository's code scanning alerts show the shipped scan only, so they list what this plugin is
responsible for and not Jenkins core's libraries.

If something the plugin genuinely ships has a vulnerability with no fix, add an entry to
[`.trivyignore`](../.trivyignore) with a reason, an upstream link and an expiry date.

## The security scan

[`jenkins-security-scan.yml`](../.github/workflows/jenkins-security-scan.yml) runs the Jenkins
project's CodeQL queries on every pull request and every push to `main`, and reports to code
scanning.

## Running the gates locally

```bash
mvn clean install

mvn -DskipTests package
mvn org.cyclonedx:cyclonedx-maven-plugin:2.9.3:makeBom \
    -DincludeProvidedScope=false -DincludeTestScope=false \
    -DoutputFormat=json -DoutputName=sbom-shipped
trivy sbom --scanners vuln --severity UNKNOWN,LOW,MEDIUM,HIGH,CRITICAL \
    --exit-code 1 target/sbom-shipped.json
```
