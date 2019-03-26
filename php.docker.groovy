imageRepository = "docker.registry.url"
imageRepositorySecret = "jenkins-regcred"
source_credentials = "bitbucket"
registry_credentials = "nexus"
jenkinsCloud = "k8s-cloud"
dockerImage = [
  name: "sample-api",
  tag: "SNAPSHOT" ]
projectSrc = [
  gitUrl: "git@git.repo.url:sample/sample-api.git",
  gitCred: "bitbucket",
  gitBranch: "staging" ]
k8sSrc = [ 
  gitUrl: "git@git.repo.url:sample/kubernetes.git",
  gitCred: "bitbucket",
  gitBranch: "master",
  path: "sample-api",
  namespace: "default",
  url: "http://some.api.url/healthcheck" ]

  def dockerFileText='''\
FROM docker.registry.url/sample:SNAPSHOT
COPY . .
RUN mkdir -p /logs && \\
    chmod -R 777 /logs && \\
    cp -f ./vhosts.conf /etc/apache2/sites-enabled/000-default.conf && \\
    chown -R www-data:www-data . && \\
    composer install --no-interaction && \\
    rm -rf /var/lib/apt/lists/* && php artisan cache:clear'''

try {
  podTemplate(label: "${dockerImage.name}-${env.BUILD_NUMBER}",
    cloud: "${jenkinsCloud}",
    imagePullSecrets: ["${imageRepositorySecret}"],
    containers: [
      containerTemplate(name: "docker",
        image: "docker",
        command: "cat",
        ttyEnabled: true,
        allwaysPullImage: true,
        resourceRequestCpu: '300m',
        resourceLimitCpu: '400m',
        resourceRequestMemory: '100Mi',
        resourceLimitMemory: '200Mi'),
      containerTemplate(name: "kubectl",
        image: "lachlanevenson/k8s-kubectl:v1.13.1",
        command: "cat",
        ttyEnabled: true,
        allwaysPullImage: true,
        resourceRequestCpu: '300m',
        resourceLimitCpu: '400m',
        resourceRequestMemory: '100Mi',
        resourceLimitMemory: '200Mi')],
    volumes: [hostPathVolume(hostPath: "/var/run/docker.sock", mountPath: "/var/run/docker.sock")]) {
    node("${dockerImage.name}-${env.BUILD_NUMBER}") {
      stage("checkout"){ 
        parallel (
          (dockerImage.name): { 
            dir("${dockerImage.name}-${env.BUILD_NUMBER}"){deleteDir()}
            dir("${dockerImage.name}-${env.BUILD_NUMBER}"){
              git url: "${projectSrc.gitUrl}", credentialsId: "${projectSrc.gitCred}", branch: "${projectSrc.gitBranch}"
              sh '''sed -i "18s|Origin\\(.*\\)|Origin '*'|g" vhosts.conf && cat vhosts.conf'''}
            dir("${dockerImage.name}-k8s-${env.BUILD_NUMBER}"){deleteDir()}
            dir("${dockerImage.name}-k8s-${env.BUILD_NUMBER}"){
              git url: "${k8sSrc.gitUrl}", credentialsId: "${k8sSrc.gitCred}", branch: "${k8sSrc.gitBranch}"}})}
      stage("build and push"){
        container("docker"){
          docker.withRegistry("https://${imageRepository}", "${registry_credentials}") {
            dir("${dockerImage.name}-${env.BUILD_NUMBER}"){
              writeFile file: "Dockerfile", text: dockerFileText
              sh "docker build -t ${imageRepository}/${dockerImage.name}:${dockerImage.tag} ."
              def app = docker.image("${imageRepository}/${dockerImage.name}:${dockerImage.tag}")
              app.push()}}}}
      stage("k8s-deploy"){container("kubectl"){withCredentials([file(credentialsId: "k8s-admin-conf", variable: "KUBECONFIG")]){
        sh "kubectl get pods"
        dir("${dockerImage.name}-k8s-${env.BUILD_NUMBER}"){
          sh """
            kubectl delete -f ${k8sSrc.path}/*-deployment.yaml --namespace=${k8sSrc.namespace} || true
            kubectl create -f ${k8sSrc.path}/*-deployment.yaml --namespace=${k8sSrc.namespace}
            kubectl get pods --namespace=${k8sSrc.namespace}"""}}}}
      stage("check deploy"){
        container("utils"){
          sh """
          count=0; until [ \$(curl -o -I -L -s -w "%{http_code}" ${k8sSrc.url}) -eq 200 ]; do sleep 5 && count=\$((\$count+1)) ; if [ \$count -eq 5 ]; then exit 1; fi; done
          """}}
      stage("slack"){
        slackSend message: "Build Succeeded - ${env.JOB_NAME} ${env.BUILD_NUMBER} (<${env.BUILD_URL}|Open>)", color: "good"}}}}
catch (Exception error) {
  echo error.getMessage()
  slackSend message: "<@BLA> Build Failed - ${env.JOB_NAME} ${env.BUILD_NUMBER} (<${env.BUILD_URL}|Open>)", color: "danger"}