CurrentDate = new Date().format( 'yyyyMMdd' )
app = [ 
  name: "some-mvc-backend",
  id: "${env.JOB_NAME}-${env.BUILD_NUMBER}".toLowerCase().replaceAll("[^a-z0-9\\-]", "")]
app.k8s = [
    cloud: "k8s-deb-2",
    label: "k8s-${app.id}", 
    registry: "some-docker-registry",
    secret: "jenkins-regcred" ]
app.src = [ 
    credentials: "jenkins-ssh",
    path: "src-${app.id}",
    subpath: "some-mvc-backend/Some.Mvc.Backend",
    url: "git@source.sample.ru:some-project/some-mvc-backend.git",  
    branch: "feature/dev" ]
app.dependencies = [ 
    credentials: "nx-nuget-apikey",
    repositoryUrl: "http://sonatytpe-nexus-repository-url/repository/nuget-hosted/"]
app.artifact = [ 
  credentials: "nx-zip-user",
  repositoryUrl: "http://sonatytpe-nexus-repository-url/repository/zip/",
  name: "${app.name}.${CurrentDate}.${env.BUILD_NUMBER}.zip" ]
app.dest = [ 
    host: "some-deploy-host",
    path: "some-mvc-backend-jenkins",
    credentials: "some-deploy-user",
    domain: "sample" ]

yamlText = """
apiVersion: v1
kind: Pod
metadata:
  name: myapp-pod
  labels:
    app: myapp
spec:
  containers:
  - name: cifs
    image: "${app.k8s.registry}/cifs:latest"
    command: ["cat"]
    tty: true
    securityContext:
      capabilities:
       add:
       - SYS_ADMIN
       - DAC_READ_SEARCH
  imagePullSecrets: ["${app.k8s.secret}"]"""

try {
  podTemplate(
    label: "${app.k8s.label}",
    cloud: "${app.k8s.cloud}",
    imagePullSecrets: ["${app.k8s.secret}"],
    containers: [ 
      containerTemplate(name: "dotnet",
        image: "microsoft/dotnet:2.2-sdk-stretch",
        command: "cat",
        ttyEnabled: true,
        allwaysPullImage: true,
        resourceRequestCpu: '1500m',
        resourceLimitCpu: '1800m',
        resourceRequestMemory: '900Mi',
        resourceLimitMemory: '1200Mi'),
      containerTemplate(name: "utils",
        image: "${app.k8s.registry}/utils:latest",
        command: "cat",
        ttyEnabled: true,
        alwaysPullImage: true,
        resourceRequestCpu: '300m',
        resourceLimitCpu: '400m',
        resourceRequestMemory: '100Mi',
        resourceLimitMemory: '200Mi')],
    yaml: yamlText) {
    node("${app.k8s.label}") {
      stage("checkout"){
        dir("${app.src.path}"){deleteDir()}
        parallel (
          (app.name): { 
            dir("${app.src.path}") {
              git url: "${app.src.url}", credentialsId: "${app.src.credentials}", branch: "${app.src.branch}"
              sh "ls -alth"}})}
      stage("build"){
        container("dotnet"){ 
          dir("${app.src.path}"){
            dir("${app.src.subpath}"){
                withCredentials([string(credentialsId: "${app.dependencies.credentials}", variable: "nugetApiKey")]){}
                sh """dotnet restore -s ${app.dependencies.repositoryUrl}
                dotnet publish -o Some.Mvc.Output"""}}}}
      stage("artifact"){
        container("utils"){
          dir("${app.src.path}"){ dir("${app.src.subpath}"){ dir("Some.Mvc.Output"){
            withCredentials([usernameColonPassword(credentialsId: "${app.artifact.credentials}", variable: "USERPASS")]){
              sh """zip -r ${app.artifact.name} .
              curl -v --user "${USERPASS}" --upload-file ./${app.artifact.name} ${app.artifact.repositoryUrl}/${app.artifact.name}
              echo "${app.artifact.name}" > ${app.name}-latest
              curl -v --user "${USERPASS}" --upload-file ${app.name}-latest ${app.artifact.repositoryUrl}/${app.name}-latest"""
              archiveArtifacts artifacts: "${app.artifact.name}", fingerprint: true
              currentBuild.displayName = "${app.artifact.name}"}}}}}}
      stage("slack"){
        slackSend message: "Build Succeeded - ${env.JOB_NAME} ${env.BUILD_NUMBER} (<${env.BUILD_URL}|Open>)", color: "good" }}}}
catch (Exception error) {
  echo error.getMessage()
  slackSend message: "<@BLA> Build Failed - ${env.JOB_NAME} ${env.BUILD_NUMBER} (<${env.BUILD_URL}|Open>)", color: "danger"}