/*
 * Builds and tests the plugin on the Jenkins project's own CI.
 * See https://github.com/jenkins-infra/pipeline-library
 */
buildPlugin(
    useContainerAgent: true,
    forkCount: '1C',
    configurations: [
        [platform: 'linux', jdk: 21],
        [platform: 'windows', jdk: 25],
    ]
)
