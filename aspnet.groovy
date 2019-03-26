CurrentDate = new Date().format( 'yyyyMMdd' )
app = [ 
  name: "some-report",
  id: "${env.JOB_NAME}-${env.BUILD_NUMBER}".toLowerCase().replaceAll("[^a-z0-9\\-]", "")]
app.k8s = [
    cloud: "k8s-deb-2",
    label: "k8s-${app.id}", 
    registry: "some-docker-registry",
    secret: "jenkins-regcred" ]
app.src = [ 
    credentials: "jenkins-ssh",
    path: "src-${app.id}",
    url: "git@source.sample.ru:some-project/some-app.git",  
    branch: "develop" ]
app.artifact = [ 
    credentials: "nx-zip-user",
    repositoryUrl: "http://sonatytpe-nexus-repository-url/repository/zip/",
    name: "${app.name}.${CurrentDate}.${env.BUILD_NUMBER}.zip" ]
app.dest = [ 
    host: "some-deploy-host",
    path: "some-report",
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

inventoryYml = """
[winrm]
${app.dest.host} ansible_connection=winrm ansible_port=5985 ansible_user="__USER__@sample.ru" ansible_password='__PASS__' ansible_winrm_server_cert_validation=ignore ansible_winrm_transport=credssp"""

taskYml = """
---
- hosts: winrm
  gather_facts: no
  tasks:
  - win_shell: cd C:\\inetpub\\some-report; .\\Uninstall.cmd
    ignore_errors: true
  - win_shell: cd C:\\inetpub\\some-report; .\\Install.cmd"""

taskYml0 = """
---
- hosts: winrm
  gather_facts: no
  tasks:
  - win_shell: >
      \$service = Get-WmiObject -Class Win32_Service -Filter "Name='Some.Sample.Service'";
      if (\$service.length -eq 0) { Write-Error -Message "Houston, we have a problem." -ErrorAction Stop }"""


try {
  podTemplate(
    label: "${app.k8s.label}",
    cloud: "${app.k8s.cloud}",
    imagePullSecrets: ["${app.k8s.secret}"],
    containers: [ 
      containerTemplate(name: "ansible",
        image: "${app.k8s.registry}/ansible:latest",
        command: "cat",
        ttyEnabled: true,
        alwaysPullImage: true,
        resourceRequestCpu: '300m',
        resourceLimitCpu: '400m',
        resourceRequestMemory: '100Mi',
        resourceLimitMemory: '200Mi'),
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
              sh "ls -alth Some.Application"
              stash "Some.Application"}})}
      stage("win_build"){
        node("MSBuild_2017") {
          dir("${app.src.path}") {         
            unstash "Some.Application"
            powershell "dir"
            ps1_cmd = """
            \$OutputEncoding = [Console]::OutputEncoding = [Text.UTF8Encoding]::UTF8
            mkdir Some.Application.Output
            & 'C:\\Program Files (x86)\\nuget' restore
            & 'C:\\Program Files (x86)\\Microsoft Visual Studio\\2017\\Community\\MSBuild\\15.0\\Bin\\MSBuild.exe' Some.Application.sln /p:OutputPath="${env.WORKSPACE}\\${app.src.path}\\Some.Application\\Some.Application.Output\\"
            ls  "${env.WORKSPACE}\\${app.src.path}\\Some.Application\\Some.Application.Output\\"
            Compress-Archive -Path ${env.WORKSPACE}\\${app.src.path}\\Some.Application\\Some.Application.Output\\* -DestinationPath .\\${app.artifact.name}
            """
            dir("Some.Application"){
              powershell script: ps1_cmd , encoding: 'UTF-8'
              stash name: "${app.artifact.name}", include: "${app.artifact.name}"
                dir("Some.Application.Output"){
                  stash "${app.id}"}}}}}
      stage("artifact"){
        container("utils"){
          dir("${app.src.path}"){ 
            withCredentials([usernameColonPassword(credentialsId: "${app.artifact.credentials}", variable: "USERPASS")]){
              unstash "${app.artifact.name}"
              sh """
              curl -v --user "${USERPASS}" --upload-file ./${app.artifact.name} ${app.artifact.repositoryUrl}/${app.artifact.name}
              echo "${app.artifact.name}" > ${app.name}-latest
              curl -v --user "${USERPASS}" --upload-file ${app.name}-latest ${app.artifact.repositoryUrl}/${app.name}-latest"""
              archiveArtifacts artifacts: "${app.artifact.name}", fingerprint: true
              currentBuild.displayName = "${app.artifact.name}"}}}}
      stage("delivery"){
        container("cifs"){
          dir("${app.src.path}"){
            withCredentials([usernamePassword(credentialsId: "${app.dest.credentials}", usernameVariable: 'deployUser', passwordVariable: 'deployPass')]){
              dir("${app.id}"){
              unstash "${app.id}"
              sh """sed -i 's|pause||g' Install.cmd UnInstall.cmd
              mkdir -p /mnt/${app.dest.host}; ls -la /mnt/
              mount -t cifs //${app.dest.host}/c\$ /mnt/${app.dest.host} \
              -o username=\${deployUser},password=\${deployPass},domain=${app.dest.domain},vers=2.0
              mkdir /mnt/${app.dest.host}/inetpub/${app.dest.path}/ || true
              ls -la /mnt/${app.dest.host}/inetpub/; ls -la ./; ls -la /mnt/${app.dest.host}/inetpub/${app.dest.path}/
              cp -R ./* /mnt/${app.dest.host}/inetpub/${app.dest.path}/
              ls -la /mnt/${app.dest.host}/inetpub/${app.dest.path}/; umount /mnt/${app.dest.host}"""}}}}}
      stage("deploy"){
        container("ansible"){
          dir("ansible-${app.id}"){
            withCredentials([usernamePassword(credentialsId: "${app.dest.credentials}", usernameVariable: 'deployUser', passwordVariable: 'deployPass')]){
            writeFile file: 'inventory.yml', text: inventoryYml
            writeFile file: 'task.yml', text: taskYml
            writeFile file: 'task0.yml', text: taskYml0
            sh """sed -i "s/__USER__/${env.deployUser}/g" inventory.yml && sed -i "s/__PASS__/${env.deployPass}/g" inventory.yml
            ansible-playbook -i inventory.yml task.yml"""
      stage("check"){sh "ansible-playbook -i inventory.yml task0.yml"}}}}}
      stage("slack"){
        slackSend message: "Build Succeeded - ${env.JOB_NAME} ${env.BUILD_NUMBER} (<${env.BUILD_URL}|Open>)", color: "good" }}}}
catch (Exception error) {
  echo error.getMessage()
  slackSend message: "<@BLA> Build Failed - ${env.JOB_NAME} ${env.BUILD_NUMBER} (<${env.BUILD_URL}|Open>)", color: "danger"}