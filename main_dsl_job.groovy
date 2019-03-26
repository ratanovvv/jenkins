def _jobs = [:] 
_jobs["sample"] = [
  [project: "java-parallel", branch: "main-dsl-job"],
  [project: "spring-music", branch: "main-dsl-job"]
]

_jobs.each{ _key, _value ->
  for( _item in _value ) {
    pipelineJob("${_key}-" + "${_item.project}".replace("_","-") + "-${_item.branch}-build") {
      definition {
        cpsScm {
          scm {
            git {
              branch("${_item.branch}")
              remote {
                url('https://github.com/ratanovvv/jenkins.git')
                // credentials('3c354c64-fabe-45ab-9bd7-dcde9b321711')
              }
            }
          }
          scriptPath ("${_key}/" + _item.project + "/${_item.branch}.groovy")
        }
      }
      triggers {
        gitlabPush {
          buildOnMergeRequestEvents(false)
          buildOnPushEvents(true)
          enableCiSkip()
          setBuildDescription()
          rebuildOpenMergeRequest('never')
          includeBranches('')
          excludeBranches('')
        }
      }
    }
  }

  listView("${_key}-view") {
    description("All ${_key} jobs")
    jobs {
        regex(/${_key}-.+/)
    }
    columns {
      status()
      weather()
      name()
      lastSuccess()
      lastFailure()
      lastDuration()
      buildButton()
    }
  }
}
