def workspaceCleaning() {
	docker.image("${nexusRegistry}:5000/centos:8").inside('-u root') {
		dir("${workspace}") {
    		sh "chown -R 997 *"
		}
	}
	deleteDir()
		dir("${workspace}@tmp") {
    		deleteDir()
		}
}

def gitClone(gitUID, gitURL, scmBranch, robotCredentialsId) {
	def useBranch = scmBranch ? scmBranch : "master"
	dir("${gitUID}") {
		git(
			url: "${gitURL}",
			branch: "${useBranch}",
			credentialsId: "${robotCredentialsId}"
		)

	}
}

return this
