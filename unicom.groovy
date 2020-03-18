// === UNICOM =====================================================================================
// ================================================================================================
// === NOTE =======================================================================================
// Do not modify anything bellow because it could affect for building/checkinh/uploading process.
// You are able to set variables in Settings part of pipeline in begining only.
// ================================================================================================
// === Contributors ===============================================================================
// Company: DIT
// Authors: Dmitry Vandenvin / Igor Kuzakov
// Version: 2.0.4
// ================================================================================================
// = GLOBAL VARIABLES =============================================================================
// === Git and Versions ===========================================================================
getGitTag = sh (returnStdout: true, script: "git describe --tags --abbrev=0").trim()
getGitBranchName = sh (returnStdout: true, script: "git rev-parse --abbrev-ref HEAD").trim()
artifactVersion = ""
// === Nexus Stuff ================================================================================
nexusDomain = "https://nexus.gkh.mos.ru"
nexusMiscRepo = "${nexusURL}/repository/misc"
nexusMvnPublicRepo = "${nexusURL}/repository/maven-public"
nexusRepoURL = "${nexusURL}/repository/dist-${projectName}/${artifactName}"
binaryName = ""
// === Nexus Artifact =============================================================================
def buildTargetDir
// === Status Messages ============================================================================
buildingSuccess = "Building successfully complete"
buildingFailed = "Building successfully failed"
currentReason = "${buildingSuccess}"
jobSuccess = "SUCCESS"
jobFailure = "FAILURE"
jobAborted = "ABORTED"
autoCancelReason = "${artifactName} ${artifactVersion} version already exists. Upgrade ${artifactName} a new version"
versionsUnequalReason = "SCM Tag ${getGitTag} and ${artifactVersion} versions are different."
autoCancelled = false
catchError = false
// ================================================================================================
// = SCRIPTS ======================================================================================
// === Get Current Date / Time ====================================================================
script {
    def now = new Date()
    currentDateTime = now.format("yyyyMMdd-HHmm")
}
// ================================================================================================
// = FUNCTIONS ====================================================================================
// === SCM Clone ==================================================================================
def gitClone() {	
	def useBranch = gitBranch ? gitBranch : "master"
	stage('Sources cloning') {
		dir("${gitUID}") {
			git(url: "${gitURL}", branch: "${useBranch}", credentialsId: "${robotCredentialsId}")
		}
	}
}
// ================================================================================================
// === Check the version ==========================================================================
def checkVersion() {
	if (compiler == "maven" || compiler == "mvn") {
		checkMavenArtifact()
	} else if (compiler == "gradle") {
		checkGradleArtifact()
	} else if (compiler == "nodejs" || compiler == "yarn") {
		checkNodeJsArtifact()
	}
	if (dockerBuild == "on" || dockerBuild == "enable" || dockerBuild == "true" || dockerBuild == true) {
		checkRegistryDocker()
	}
}
def checkMavenArtifact() {
	dir("${gitUID}") {
	    componentGroupId = sh (returnStdout: true, script: '''xmllint --xpath "//*[local-name()='project']/*[local-name()='GroupId']/text()" pom.xml | tr / .''').trim()
	    artifactVersion = sh (returnStdout: true, script: '''xmllint --xpath "//*[local-name()='project']/*[local-name()='version']/text()" pom.xml''').trim()
	    componentArtifactId = sh (returnStdout: true, script: '''xmllint --xpath "//*[local-name()='project']/*[local-name()='artifactId']/text()" pom.xml''').trim()		
		if (getGitTag != artifactVersion) {
			versionsUnequal()
		}
	    def checkURL = "${nexusMvnPublicRepo}/${componentGroupId}/${artifactVersion}/${componentArtifactId}"
	    currentVersionExists = sh (returnStdout: true, script: "if curl --output /dev/null --silent --head --fail ${nexusRepoURL}/${artifactVersion}/${artifactName}-${artifactVersion}.tgz; then echo 1; else echo 0; fi").trim()
	    for (filetype in ["war", "jar", "ear"]) {
	    	currentMvnExists = sh (returnStdout: true, script: "if curl --output /dev/null --silent --head --fail ${checkURL}.${filetype}; then echo 1; else echo 0; fi").trim()
	    }
	    if (currentVersionExists == '1') {
	        currentReason = "${artifactName} ${artifactVersion} version already exists. Upgrade ${artifactName} project pox.xml with a new version"
	        echo "${currentReason}"
	        autoCancelled = true
	        error('App version already exists')
	    }
	}
}
def checkRegistryDocker(){
    dockerImageChk = sh (returnStdout: true, script: '''docker manifest inspect '''+nexusRegistry+''':5000/'''+dockerName+''':'''+dockerTag+''' > /dev/null ; echo ?''').trim()
    if (dockerImageChk == "1") {
		autoCancel()   	
    }
}
// === Abort ======================================================================================
def autoCancel() {    
    currentReason = "${autoCancelReason}"
    echo "${currentReason}"
    autoCancelled = true
    error('App version already exists')
}
def versionsUnequal() {
    currentReason = "${versionsUnequalReason}"
    echo "${currentReason}"
    autoCancelled = true
    error('App version already exists')	
}
// ================================================================================================
// === Set Environment ============================================================================
def setBuildingEnv() {
	def javaHome = useJDK == "java-11*" ? "/usr/lib/jvm/java-11" : "/usr/java/default"
	def jdk = useJDK ? useJDK : "jdk1.8"
	sh "yum install ${jdk} -y"
    env.JAVA_HOME="${javaHome}"
    env.JRE_HOME="${env.JAVA_HOME}/jre"
    env.M2_HOME="/opt/maven"
    env.MAVEN_HOME="/opt/maven"
    env.PATH="${env.PATH}:${env.M2_HOME}/bin:${env.JAVA_HOME}/bin"
}
// ================================================================================================
// === Prepare Docker Environment for Building ====================================================
def dockerPrepare() {
	def repo = useLinux == "centos:7" ? "centos7-proxy.repo" : "devops.repo"
	sh "rm -rf /etc/yum.repos.d/*"
	sh "curl --get ${nexusMiscRepo}/rpm/${repo} --output /etc/yum.repos.d/${repo}"
	sh "curl --get ${nexusMiscRepo}/npm/npmrc --output ~/.npmrc"
	sh "yum install git langpacks-en glibc-all-langpacks unzip zip wget ${buildDependencies} -y"
	if (compiler == "maven" || compiler == "mvn") {
		prepareMaven()
	} else if (compiler == "gradle") {
		prepareGradle()
	} else if (compiler == "nodejs") {
		prepareNodejs()
	} else if (compiler == "yarn") {
		prepareYarn()
	}
}
// === Prepare Maven ===
def prepareMaven() {
	def useMvnVersion = compilerVersion ? compilerVersion : "3.6.3"
	def mvnBin = "apache-maven-${useMvnVersion}-bin.tar.gz"
	sh "mkdir -p ~/.m2/repository"
	sh "curl --get ${nexusMiscRepo}/mvn/settings.xml --output ~/.m2/settings.xml"
	sh "curl --get ${nexusMiscRepo}/tools/${mvnBin} --output ~/${mvnBin}"
	sh "tar xf ~/${mvnBin} -C /opt"
	sh "rm -rf ~/${mvnBin}"
	sh "ln -s /opt/apache-maven-${useMvnVersion} /opt/maven"
}
// === Prepare Gradle ===
def prepareGradle() {
	def useGradleVersion = compilerVersion ? compilerVersion : "6.2.2"
	def gradleBin = "gradle-${useGradleVersion}.tar.gz"
 	sh "mkdir -p ~/.m2/repository"	
 	sh "mkdir -p ~/.gradle"
 	sh "curl --get ${nexusMiscRepo}/mvn/settings.xml --output ~/.m2/settings.xml"
 	sh "curl --get ${nexusMiscRepo}/gradle/init.gradle --output ~/.gradle/init.gradle"
 	sh "curl --get ${nexusMiscRepo}/tools/${gradleBin} --output ~/${gradleBin}"
	sh "tar xf ~/${gradleBin} -C /opt"
	sh "rm -rf ~/${gradleBin}"
	sh "ln -s /opt/gradle-${useGradleVersion} /opt/gradle"
}
// === Prepare NodeJs ===
def prepareNodejs() {
	def useNodeJsVersion
	if (useLinux == "centos:7") {
		useNodeJsVersion = compilerVersion ? compilerVersion : "10.16"
	} else if (useLinux == "centos:8") {
		useNodeJsVersion = compilerVersion ? compilerVersion : "10.19"
	}
	sh "yum install nodejs-${useNodeJsVersion}* -y"
}
// === Prepare Yarn ===
def prepareYarn() {
	def useYarnVersion = compilerVersion ? compilerVersion : "1.22.4"
	sh "yum install yarn-${useYarnVersion}* -y"
}
// ================================================================================================
// === Builders ===================================================================================
def startBuilding() {
	dir("${gitUID}") {
		if (compiler == "maven" || compiler == "mvn") {
			buildMaven()
		} else if (compiler == "gradle") {
			buildGradle()
		} else if (compiler == "nodejs") {
			buildNodejs()
		} else if (compiler == "yarn") {
			buildYarn()
		}
	}
}
// === Maven ===
def buildMaven() {
	def defaultCharset = compilerCharset ? compilerCharset : "UTF-8"
	def mvnCleanInstall = compilerBuildOption ? compilerBuildOption : "mvn clean install"
    sh "export MAVEN_OPTS='-Dhttps.protocols=TLSv1,TLSv1.1,TLSv1.2 -Dfile.encoding=${defaultCharset} -Dsun.jnu.encoding=${defaultCharset}'"
    sh '''export PATH='''+env.PATH+''' && '''+mvnCleanInstall+''''''
    sh "chown -R 997 *"
    mavenDeploy()
}
// === Gradle ===
def buildGradle() {
	def gradleCleanInstall = compilerBuildOption ? compilerBuildOption : "gradle clean build"
	sh '''export PATH='''+env.PATH+''':/opt/gradle/bin && '''+gradleCleanInstall+''''''
	sh "chown -R 997 *"
	mavenDeploy()
}
// === NodeJs ===
def npmRunBuildScript() {
	if (changeFolder !=""){
		sh "cd ${changeFolder[0]}"
	}
	for (npmRunBuildItem in compilerBuildOption) {
	    sh "npm install -g npm"
	    sh "npm install"
	    sh "npm run build:${npmRunBuildItem}"
	    sh "mv ./${buildTargetDir}/ ./${buildTargetDir}_${npmRunBuildItem}/"
	    sh "chown -R 997 ${buildTargetDir}_${npmRunBuildItem}"
	    sh "rm -rf node_modules"		
	}
}
def npmRunBuildClean(){
	sh "npm install -g npm"
    sh "npm install"
    sh "npm run build"
    sh "chown -R 997 *"
    sh "rm -rf node_modules"
}
def buildNodejs() {
	buildTargetDir = buildTarget ? buildTarget : "dist"
	compilerBuildOption ? npmRunBuildScript() : npmRunBuildClean()
}
// === Yarn ===
def yarnRunBuildScript() {
	buildTargetDir = buildTarget ? buildTarget : "build"
	for ( yarnRunBuildItem in yarnRunBuild ) {
	    sh "yarn install"
	    sh "yarn build:${yarnRunBuildItem}"
	    sh "mv ./${buildTargetDir}/ ./${buildTargetDir}_${yarnRunBuildItem}/"
	    sh "chown -R 997 ${buildTargetDir}_${yarnRunBuildItem}"
	    sh "rm -rf node_modules"		
	}
}
def yarnBuildClean() {
    sh "yarn install"
    sh "yarn build"
    sh "chown -R 997 *"
    sh "rm -rf node_modules"

}
def buildYarn() {
	def yarnRunBuild = compilerBuildOption ? yarnRunBuildScript() : yarnRunBuildClean()

}
// === Maven Artifacts Deploying ===
def mavenDeploy() {
	sh "cp ${workspace}/devkins/scripts/mavenimport.sh ~/.m2/repository/mavenimport.sh"
	sh "chmod +x ~/.m2/repository/mavenimport.sh"
	withCredentials([string(credentialsId: "${nexusPushPassID}", variable: "PSWD")]) {
		for (repotype in ["releases", "snapshots"]) {
			sh "sh ~/.m2/repository/mavenimport.sh -u Robot -p ${env.PSWD} -r ${nexusURL}/repository/maven-${repotype}"
		}
	}
}
///=== CryptoService ==============================================================================
def installJCP() {
	def javaHome = useJDK == "java-11*" ? "/usr/lib/jvm/java-11" : "/usr/java/default"
	sh "curl --get ${nexusMiscRepo}/cryptopro/jcp-2.0.40035.zip --output ~/jcp-2.0.40035.zip"
	withCredentials([string(credentialsId: "crypto-pro-serial", variable: "SECRET_KEY")]) {
	    sh'''
	        cd ~
	        unzip jcp-2.0.40035.zip
	        cd jcp-2.0.40035
	        chmod +x setup_console.sh
	        sh setup_console.sh '''+javaHome+''' -force -en -install -jre '''+javaHome+''' -jcp -jcryptop -strict_mode -serial_jcp $SECRET_KEY
	    '''
    }
}
// === Rub Building ===
def runBuilding() {
	if (compiler == "gradle" || compiler == "maven" || compiler == "mvn") {
    	setBuildingEnv()
		if (useJCP == "on" || useJCP == "enable" || useJCP == "true" || useJCP == true) {		
			installJCP()
		}
    }
	startBuilding()
}
// ================================================================================================
// === Sonarqube Checking =========================================================================
def sonarCheckingIn() {
	dir("${gitUID}") {
		if (sonarChecking == "on" || sonarChecking == "enable" || sonarChecking == "true" || sonarChecking == true) {
			if (compiler == "maven" || compiler == "mvn") {
				sonarMaven()
			} else if (compiler == "gradle") {
				sonarGradle()
			}
		}
	}
}
def sonarCheckingOut() {
	dir("${gitUID}") {
		if (sonarChecking == "on" || sonarChecking == "enable" || sonarChecking == "true" || sonarChecking == true) {
			sonarNodejs()
		}
	}
}
// === Maven Checking ===
def sonarMaven() {
	def javaHome = useJDK == "java-11*" ? "/usr/lib/jvm/java-11" : "/usr/java/default"
    env.JAVA_HOME="${javaHome}"
    env.JRE_HOME="${env.JAVA_HOME}/jre"
    env.M2_HOME="/opt/maven"
    env.MAVEN_HOME="/opt/maven"
    env.PATH="${env.PATH}:${env.M2_HOME}/bin:${env.JAVA_HOME}/bin"	
    sh "export MAVEN_OPTS='-Dhttps.protocols=TLSv1,TLSv1.1,TLSv1.2'"
    sh '''
    	export PATH='''+env.PATH+'''
    	mvn sonar:sonar -Dsonar.sourceEncoding=UTF-8 -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Dsonar.projectKey='''+sonarKey+''' -Dsonar.projectName='''+sonarProjectName+''' -Dsonar.host.url='''+sonarURL+''' -Dsonar.login='''+sonarToken+''' --legacy-local-repository
 	'''
 	sh "chown -R 997 *"
}
// === Gradle Checking ===
def sonarGradle() {
	def javaHome = useJDK == "java-11*" ? "/usr/lib/jvm/java-11" : "/usr/java/default"
	env.JAVA_HOME="${javaHome}"
	sh '''
	cp build.gradle build.gradle.bk
	echo 'buildscript {' > sonar.cfg
	echo -e '\trepositories {' >> sonar.cfg
	echo -e '\tmaven {' >> sonar.cfg
	echo -e '\t\turl "https://plugins.gradle.org/m2/"' >> sonar.cfg
	echo -e '\t}' >> sonar.cfg
	echo -e '}' >> sonar.cfg
	echo -e '\tdependencies {' >> sonar.cfg
	echo -e '\t\tclasspath "org.sonarsource.scanner.gradle:sonarqube-gradle-plugin:2.8"' >> sonar.cfg
	echo -e '\t}' >> sonar.cfg
	echo -e '}' >> sonar.cfg
	echo 'apply plugin: "org.sonarqube"' >> sonar.cfg
	cat sonar.cfg > build.gradle
	cat build.gradle.bk >> build.gradle
	export JRE_HOME="$JAVA_HOME/jre"
	export PATH="$JAVA_HOME/bin:$PATH"
	export M2_HOME=/opt/maven
	export MAVEN_HOME=/opt/maven
	export PATH=${M2_HOME}/bin:${PATH}
	export PATH=$PATH:/opt/gradle/bin
	gradle -x test sonarqube -Dsonar.projectKey='''+sonarKey+''' -Dsonar.host.url='''+sonarURL+''' -Dsonar.login='''+sonarToken+''' -Dsonar.projectName='''+sonarProjectName+'''
    '''
    sh "chown -R 997 *"
    sh "rm -rf .gradle"
}
// === NodeJs and Yarn Checking ===
def sonarNodejs() {
    environment {
        scannerHome = tool 'sonarqubescanner'
    } 
    withSonarQubeEnv('Sonar_DIT') { 
        sh "${sonarPath}/sonar-scanner \
            -Dsonar.projectKey=${sonarKey} \
            -Dsonar.sources=. \
            -Dsonar.host.url=${sonarURL} \
            -Dsonar.login=${sonarToken} \
            -Dsonar.projectName=${sonarProjectName}"
    }
}
// ================================================================================================
// === Nexus Uploading ============================================================================
def nexusDefaultUploading() {
	def uploadLink = "${nexusRepoURL}/${artifactVersion}/${binaryName}"
	withCredentials([usernameColonPassword(credentialsId: "${robotNexus}", variable: "USERPASS")]) {
		sh "curl -u ${env.USERPASS} --upload-file ${binaryName} ${uploadLink}"
	}
}

def nexusUploading() {
	if (nexusBinUpload == "on" || nexusBinUpload == "enable" || nexusBinUpload == "true" || nexusBinUpload == true) {
		dir("${gitUID}") {
			if (compiler == "maven" || compiler == "mvn") {
				buildTargetDir = buildTarget ? buildTarget : "target/*.?ar"
				artifactVersion = sh (returnStdout: true, script: '''xmllint --xpath "//*[local-name()='project']/*[local-name()='version']/text()" pom.xml''').trim()
				binaryName = "${artifactName}-${artifactVersion}.zip"
				sh "zip -j ${binaryName} ${buildTargetDir}"
				nexusDefaultUploading()
			} else if (compiler == "gradle") {
				buildTargetDir = buildTarget ? buildTarget : "build/*.?ar"
				artifactVersion = "${getGitTag}"
				binaryName = "${artifactName}-${artifactVersion}.zip"
				sh "zip -j ${binaryName} ${buildTargetDir}"
				nexusDefaultUploading()
			} else if (compiler == "nodejs" || compiler == "yarn") {
				def buildTargetDir
				def zipTargetDir
				if (compiler == "nodejs") {
					buildTargetDir = buildTarget ? buildTarget : "dist"
				} else if (compiler == "yarn") {
					buildTargetDir = buildTarget ? buildTarget : "build"	
				}
				artifactVersion = sh (returnStdout: true, script: '''node -pe "require('./package.json').version"''').trim()
				binaryName = "${artifactName}-${artifactVersion}.zip"
				zipTargetDir = "${buildTargetDir}"
				if (compilerBuildOption != "" && compilerBuildOption.isEmpty() == false) {
					for (buildItem in compilerBuildOption) {
						binaryName = "${buildItem}-${artifactName}-${artifactVersion}.zip"
						zipTargetDir = "${buildTargetDir}_${buildItem}"
						sh "zip -r ${binaryName} ${zipTargetDir}/*"
						nexusDefaultUploading()
					}
				} else {
					sh "zip -r ${binaryName} ${zipTargetDir}/*"
					nexusDefaultUploading()
				}
			}
		}
	}
}
// ================================================================================================
// === NOTIFICATIONS ==============================================================================
def sendNotify() {
	sendMessage = "${currentReason}"
	if (compiler == "") { nexusBinUpload == false }
	if (compiler != "none" && nexusBinUpload == "on" || nexusBinUpload == "enable" || nexusBinUpload == "true" || nexusBinUpload == true) {
		nexusRepoURL = "${nexusDomain}/repository/dist-${projectName}/${artifactName}"
		uploadLink = "${nexusRepoURL}/${artifactVersion}/${binaryName}"
		if (compilerBuildOption != "" && compilerBuildOption.isEmpty() == false && compiler == "nodejs" || compiler == "yarn") {
			uploadLink = "${nexusRepoURL}/${artifactVersion}"
		}
		sendMessage = "${currentReason}\n${uploadLink}"
	}
	if (catchError == true) {
		sendMessage = "${currentReason}"
	}
    notificationMail()
    notificationZulip()
}

def notificationZulip() {
	if (toChat == "on" || toChat == "enable" || toChat == "true" || toChat == true) {
		zulipNotification stream: 'DevOps', topic: 'jenkins', smartNotification: 'disabled'
	    zulipSend message: "${sendMessage}"
	}
}

def notificationMail() {
    if (toMail == "on" || toMail == "enable" || toMail == "true" || toMail == true) {
        mail to: "${emailDevOPS}",
        subject: "[Jenkins] ${artifactName} / ${gitBranch} / Job#: ${currentBuild.number} / ${currentBuild.result}",
        body: """=== General Information ===\nApplication: ${artifactName}\nBranch: ${gitBranch}\nJod Build#: ${currentBuild.number}\nResult: ${currentBuild.result}\n=== Console Output ===\nJob Build# ${currentBuild.number} Log: ${jenkinsJobURL}/${currentBuild.number}/consoleText"""
	}
}
// ================================================================================================
// === Cleaning ===================================================================================
def workspaceCleaning() {
	stage('Workspace cleaning') {
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
}
// === Job Status =================================================================================
 def statusSuccess() {
    currentBuild.result = "${jobSuccess}"
	currentReason = "${buildingSuccess}"	
}
def statusFailed() {
    if (autoCancelled) {
        currentBuild.result = "${jobAborted}"
        return
    }
    currentBuild.result = "${jobFailure}"
    currentReason = "${buildingFailed}"
    catchError = true
}
// ================================================================================================
// === Docker Inside ==============================================================================
def runMagic() {
    try {
        gitClone()
        if (compiler != "") {
			docker.image("${nexusRegistry}:5000/${useLinux}").inside('-u root') {
			    stage('Building Docker preparing') { dockerPrepare() }
			    stage('Binaries building') { runBuilding() }
			    if (compiler != "nodejs") {
			    	if (compiler != "yarn") {
			    		echo "inside compiler ${compiler}"
			    		stage('Sonarqube checking') { sonarCheckingIn() }
			    	}
			    }
			}
		}
		if (compiler == "nodejs" || compiler == "yarn" || compiler == "") {
			echo "outside"
			stage('Sonarqube checking') { sonarCheckingOut() }
		}
		def checkNexusBinUpload = nexusBinUpload ? nexusBinUpload : "off"
		if (checkNexusBinUpload != "off" ) {
			stage('Nexus uploading') { nexusUploading() }
		}
		// uDeploy
		statusSuccess()
    }
    catch (error) {
		statusFailed()    	
	    throw error
    }
    finally {
        workspaceCleaning()
        sendNotify()
    }
}
// ================================================================================================
return this
// ================================================================== May the force be with you ===
// ============================================================ DevOps = 2020 = Made by 1denwin ===
