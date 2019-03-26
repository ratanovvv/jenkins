import org.csanchez.jenkins.plugins.kubernetes.pipeline.PodTemplateAction

def clearTemplateNames() {
  currentBuild.rawBuild.getAction( PodTemplateAction.class )?.stack?.clear()
}

def cntarray = [containerTemplate(name: 'selenium-hub', image: 'selenium/hub:3.4.0')]
    cntarray.push(containerTemplate(name: 'maven-firefox', image: 'maven:3.3.9-jdk-8-alpine', ttyEnabled: true, command: 'tail', args: '-f /dev/null'))
    cntarray.push(containerTemplate(name: 'maven-chrome', image: 'maven:3.3.9-jdk-8-alpine', ttyEnabled: true, command: 'tail', args: '-f /dev/null'))
    cntarray.push(containerTemplate(name: 'selenium-chrome', image: 'selenium/node-chrome:3.4.0', envVars: [
        containerEnvVar(key: 'HUB_PORT_4444_TCP_ADDR', value: 'localhost'),
        containerEnvVar(key: 'HUB_PORT_4444_TCP_PORT', value: '4444'),
        containerEnvVar(key: 'DISPLAY', value: ':99.0'),
        containerEnvVar(key: 'SE_OPTS', value: '-port 5556')
      ]))
    cntarray.push(containerTemplate(name: 'selenium-firefox', image: 'selenium/node-firefox:3.4.0', envVars: [
        containerEnvVar(key: 'HUB_PORT_4444_TCP_ADDR', value: 'localhost'),
        containerEnvVar(key: 'HUB_PORT_4444_TCP_PORT', value: '4444'),
        containerEnvVar(key: 'DISPLAY', value: ':98.0'),
        containerEnvVar(key: 'SE_OPTS', value: '-port 5557')
      ]))

def labels = ['1','2'] // labels for Jenkins node types we will build on
def builders = [:]
for (x in labels) {
    def label = x // Need to bind the label variable before the closure - can't do 'for (label in labels)'

    // Create a map to pass in to the 'parallel' step so we can fire all the builds at once
    builders[label] = {
        label: { 
            try { clearTemplateNames(); } catch (err) { echo "clearTemplateNames failed" }
            podTemplate(label: "generated-$BUILD_NUMBER-$label", cloud: 'jenkins', namespace: "jenkins", containers: cntarray) {
                node("generated-$BUILD_NUMBER-$label") {
                    unstash 'scm-files'
                    unzip 'scm-files.zip'
                    container('maven-firefox') {
                        stage('Test firefox') {
                          sh 'mvn -B clean test -Dselenium.browser=firefox -Dsurefire.rerunFailingTestsCount=5 -Dsleep=0'
                        }
                      }
                    container('maven-chrome') {
                        stage('Test chrome') {
                          sh 'mvn -B clean test -Dselenium.browser=chrome -Dsurefire.rerunFailingTestsCount=5 -Dsleep=0'
                        }
                      }
                    containerLog("selenium-firefox")
                    containerLog("selenium-chrome")
                }
            }
        }
    }
}

podTemplate(
                label: "git-${env.BUILD_NUMBER}",
                cloud: "jenkins",
                namespace: "jenkins",
                containers: [
                        containerTemplate(name: "git", image: "alpine/git", command: "cat", ttyEnabled: true)
                ],
                volumes: [hostPathVolume(hostPath: "/var/run/docker.sock", mountPath: "/var/run/docker.sock")]
    ) {

            node("git-${env.BUILD_NUMBER}") {
            // node('master'){
                container('git'){
                    stage('wipe ws') { dir('scm'){ deleteDir() } }
                    stage('checkout') { 
                        dir('scm') {
                            git 'https://github.com/carlossg/selenium-example.git'
                            zip dir: '.', glob: '', zipFile: 'scm-files.zip'
                            stash includes: 'scm-files.zip', name: 'scm-files'
                        }
                    }
                }
            }
        }

stage('Pods'){
    parallel builders
}