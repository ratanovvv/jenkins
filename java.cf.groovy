imageRepository = "docker.registry.url"
imageRepositorySecret = "jenkins-regcred"
source_credentials = "bitbucket"
registry_credentials = "nexus"
maven_repository = "http://sonatytpe-nexus-repository-url/repository/maven-snapshots"
docker.host = "tcp://1.1.1.1:2375"
artifact = [
  name: "some-cf-application",
  gitUrl: "git@source.sample.ru:some-project/some-java-cf-app.git",
  gitCred: "jenkins",
  gitBranch: "master",
  deployment: [url: "${artifact.name}-from-jenkins.cloud-foundry.url"] ]

def shDeploy = """rm -f manifest.yml && echo "nameserver 1.1.1.53" > /etc/resolv.conf
cf api  https://api.cloud-foundry.url --skip-ssl-validation
cf auth \${cfUser} \${cfPass}
cf t -o some-cf-org -s some-cf-space
cf d -r -f ${artifact.name}-from-jenkins
cf push --no-start ${artifact.name}-from-jenkins -p \$(find . -name '*.jar')
cf set-env ${artifact.name}-from-jenkins UAA_CLIENT_ID some-app
cf set-env ${artifact.name}-from-jenkins UAA_CLIENT_SECRET some-app-secret
cf bind-service ${artifact.name}-from-jenkins uaa-service-instance
cf bind-service ${artifact.name}-from-jenkins s3-service-instance
cf bind-service ${artifact.name}-from-jenkins postgres-service-instance
cf bind-service ${artifact.name}-from-jenkins kafka-service-instance
cf start ${artifact.name}-from-jenkins"""

def shBuildAndPush = """sed -i "s|__MAVEN_USER__|\${mavenUser}|g" settings.xml && \\
sed -i "s|__MAVEN_PASS__|\${mavenPass}|g" settings.xml && \\
mkdir /root/.m2 && cat settings.xml && cp settings.xml /root/.m2/ && \\
mvn -Dmaven.test.skip=true -f ./some-app/pom.xml package -X; find . -name '*.jar'; find . -name '*.war'
mvn deploy:deploy-file -Dmaven.test.skip=true -DgeneratePom=false -DrepositoryId=SampleMavenSnapshots \\
-Durl=${maven_repository} \\
-DpomFile=./some-app/pom.xml -Dfile=\$(find . -name '*.jar') -X"""

settingsXmlText = """<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0
                      https://maven.apache.org/xsd/settings-1.0.0.xsd">
  <servers>
    <server>
      <id>SampleMavenSnapshots</id>
      <username>__MAVEN_USER__</username>
      <password>__MAVEN_PASS__</password>
    </server>
    <server>
      <id>SampleMavenPublic</id>
      <username>__MAVEN_USER__</username>
      <password>__MAVEN_PASS__</password>
    </server>
  </servers>
</settings>"""

try {
  podTemplate(
    label: "k8s-${env.JOB_NAME}-${env.BUILD_NUMBER}",
    cloud: 'k8s-deb-2',
    imagePullSecrets: ["${imageRepositorySecret}"],
    containers: [
      containerTemplate(name: "maven",
        image: "maven:3-jdk-11-slim",
        command: "cat",
        ttyEnabled: true,
        allwaysPullImage: true,
        resourceRequestCpu: '1500m',
        resourceLimitCpu: '1800m',
        resourceRequestMemory: '900Mi',
        resourceLimitMemory: '1200Mi'),
      containerTemplate(name: "cf",
        image: "${imageRepository}/cf-cli:0.0.1",
        command: "cat",
        ttyEnabled: true,
        allwaysPullImage: true,
        resourceRequestCpu: '300m',
        resourceLimitCpu: '400m',
        resourceRequestMemory: '100Mi',
        resourceLimitMemory: '200Mi')]) {
    node("k8s-${env.JOB_NAME}-${env.BUILD_NUMBER}") {
      dir("checkout"){
        stage("checkout"){ 
          parallel (
            (artifact.name): { 
              dir("${artifact.name}"){deleteDir()}
              dir("${artifact.name}"){
                git url: "${artifact.gitUrl}", credentialsId: "${artifact.gitCred}", branch: "${artifact.gitBranch}"}})}
        stage("build and push"){ 
          container("maven"){
            dir("${artifact.name}"){
              withCredentials([
                usernamePassword(
                credentialsId: "maven",
                usernameVariable: "mavenUser",
                passwordVariable: "mavenPass")]){
                withEnv(["DOCKER_HOST=${docker.host}"]){
                  writeFile file: "settings.xml", text: settingsXmlText
                  sh shBuildAndPush}}}}}
        stage("cf push"){
          container("cf"){
            dir("${artifact.name}"){
              withCredentials([
              usernamePassword(
              credentialsId: "cf",
              usernameVariable: 'cfUser',
              passwordVariable: 'cfPass')]){
                sh shDeploy}}}}}
        stage("check deploy"){
          container("utils"){
            sh """
            count=0; until [ \$(curl -o -I -L -s -w "%{http_code}" ${artifact.deployment.url}) -eq 200 ]; do sleep 5 && count=\$((\$count+1)) ; if [ \$count -eq 5 ]; then exit 1; fi; done
            """}}
        stage("slack"){
          slackSend message: "Build Succeeded - ${env.JOB_NAME} ${env.BUILD_NUMBER} (<${env.BUILD_URL}|Open>)", color: "good"}}}}
catch (Exception error) {
  echo error.getMessage()
  slackSend message: "<@BLA> Build Failed - ${env.JOB_NAME} ${env.BUILD_NUMBER} (<${env.BUILD_URL}|Open>)", color: "danger"}                