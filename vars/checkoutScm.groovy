import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;
import hudson.plugins.git.GitException
import hudson.AbortException

/**
 * Block which wraps processing of checkout SCM
 *
 * @param checkoutOptions Map with options (it's used for support optional params)
 * Possible elements:
 *     checkoutClass - class for checkout SCM: GitSCM or SubversionSCM
 *     repositoryUrl - link to repository url (ssh or https). SSH is recomended. 
 *     branchName - repository branch name. You can use origin/master or master, both are fine. 
 *     credentialsId  - Jenkins credentials name for accessing your repository
 *     disableSubmodules - disable downloading git submodules 
 *     recursiveSubmodules - download submodules of your repository submodule (use with submoduleDepth options)
 *     submoduleDepth - count of recursive submodules downloads
 *     useLFS - enable Git LFS option (Large File Storage)
 *     prRepoName - PR repository url for mergePR function (option for manual jobs)
 *     prBranchName - PR braanch name for mergePR function (option for manual jobs)
 *     cleanCheckout - clear all untracked files or not (default - true)
 *     isGerritMainRepo - is used to mark main repo stored in Gerrit. This parameter is necessary to process env.GERRIT_REFSPEC param
 */

def call(Map checkoutOptions) {
    
    checkoutOptions['checkoutClass'] = checkoutOptions['checkoutClass'] ?: 'GitSCM'
    checkoutOptions['branchName'] = checkoutOptions['branchName'] ?: ''
    checkoutOptions['credentialsId'] = checkoutOptions['credentialsId'] ?: 'radeonprorender'
    
    checkoutOptions['disableSubmodules'] = checkoutOptions['disableSubmodules'] ?: false
    checkoutOptions['recursiveSubmodules'] = checkoutOptions.containsKey('recursiveSubmodules') ? checkoutOptions['recursiveSubmodules'] : true
    checkoutOptions['submoduleDepth'] = checkoutOptions['submoduleDepth'] ?: 2

    checkoutOptions['useLFS'] = checkoutOptions['useLFS'] ?: false
    checkoutOptions['isGerritMainRepo'] = checkoutOptions['isGerritMainRepo'] ?: false

    checkoutOptions['prBranchName'] = checkoutOptions['prBranchName'] ?: ''
    checkoutOptions['prRepoName'] = checkoutOptions['prRepoName'] ?: ''

    Boolean cleanCheckout = checkoutOptions.containsKey('cleanCheckout') ? checkoutOptions['cleanCheckout'] : true 

    try {
        if (checkoutOptions['checkoutClass'] == 'GitSCM') {
            checkoutGitScm(checkoutOptions, cleanCheckout)
        } else if (checkoutOptions['checkoutClass'] == 'SubversionSCM') {
            checkoutSubversionScm(checkoutOptions)
        }
    } catch (FlowInterruptedException e) {
        println "[INFO] Task was aborted during checkout"
        throw e
    } catch (e) {
        if (cleanCheckout) {
            println "[ERROR] Failed to checkout git on ${env.NODE_NAME}. Cleaning workspace and try again."
            println(e.toString())
            println(e.getMessage())
            cleanWS()
            if (checkoutOptions['checkoutClass'] == 'GitSCM') {
                checkoutGitScm(checkoutOptions, true)
            } else if (checkoutOptions['checkoutClass'] == 'SubversionSCM') {
                checkoutSubversionScm(checkoutOptions)
            }
        } else {
            // non-clean checkout failed
            throw e
        }
    }
}


def checkoutGitScm(Map checkoutOptions, Boolean cleanCheckout=true) {

    // Use SCM if checkoutOptions['branchName'] is '', else use checkoutOptions['branchName'] in speacial format
    def repositoryBranch = checkoutOptions['branchName'] ? [[name: checkoutOptions['branchName']]] : scm.branches

    println "[INFO] Repository URL: ${checkoutOptions['repositoryUrl']}"
    println "[INFO] Repository branch: ${repositoryBranch}"
    println "[INFO] Submodules processing: ${!checkoutOptions['disableSubmodules']}"
    println "[INFO] Recursive submodules: ${checkoutOptions['recursiveSubmodules']}"

    if (checkoutOptions['prBranchName']) {
        println "[INFO] PR branch name: ${checkoutOptions['prBranchName']}"
        println "[INFO] PR repository: ${checkoutOptions['prRepoName']}"
    }

    List configs = null
    if (env.GERRIT_REFSPEC && checkoutOptions["isGerritMainRepo"]) {
        configs = [[credentialsId: checkoutOptions['credentialsId'],
                    url: checkoutOptions['repositoryUrl'],
                    refspec: "${env.GERRIT_REFSPEC}:${env.GERRIT_REFSPEC}"]]
    } else {
        configs = [[credentialsId: checkoutOptions['credentialsId'],
                    url: checkoutOptions['repositoryUrl']]]
    }

    def checkoutExtensions = [
            [$class: 'PruneStaleBranch'],
            [$class: 'CheckoutOption', timeout: 60],
            [$class: 'AuthorInChangelog'],
            [$class: 'CloneOption', timeout: 120, noTags: false],
            [$class: 'SubmoduleOption', disableSubmodules: checkoutOptions['disableSubmodules'],
             parentCredentials: true, recursiveSubmodules: checkoutOptions['recursiveSubmodules'], shallow: true, 
             depth: checkoutOptions['submoduleDepth'], timeout: 60, reference: '', trackingSubmodules: false],
    ]

    if (cleanCheckout) {
        checkoutExtensions.add([$class: 'CleanBeforeCheckout'])
        checkoutExtensions.add([$class: 'CleanCheckout', deleteUntrackedNestedRepositories: true])
    }

    if (checkoutOptions['prBranchName']) {
        if (checkoutOptions['prRepoName'] && checkoutOptions['repositoryUrl'] != checkoutOptions['prRepoName']) {
            configs.add([credentialsId: checkoutOptions['credentialsId'], url: checkoutOptions['prRepoName'], name: 'remoteRepo'])
            checkoutExtensions.add([$class: 'PreBuildMerge', options: [mergeTarget: checkoutOptions['prBranchName'], mergeRemote: 'remoteRepo']])
        } else {
            checkoutExtensions.add([$class: 'PreBuildMerge', options: [mergeTarget: checkoutOptions['prBranchName'], mergeRemote: 'origin']])
        }
    }

    if (checkoutOptions['useLFS']) checkoutExtensions.add([$class: 'GitLFSPull'])

    // init sparse checkout
    if (checkoutOptions['SparseCheckoutPaths']) {
        def sparseCheckoutPaths = []

        checkoutOptions['SparseCheckoutPaths'].each() { path ->
            sparseCheckoutPaths.add([$class: 'SparseCheckoutPath', path: path])
        }

        checkoutExtensions.add([$class: 'SparseCheckoutPaths', sparseCheckoutPaths: sparseCheckoutPaths])
    }

    // !branchName need for ignore merging testing repos (jobs_test_*) 
    if (!checkoutOptions['branchName'] && env.BRANCH_NAME && env.BRANCH_NAME.startsWith("PR-")) {
        checkout scm
    } else {
        checkout changelog: true, poll: false,
            scm: [$class: checkoutOptions['checkoutClass'], branches: repositoryBranch, doGenerateSubmoduleConfigurations: false,
                    extensions: checkoutExtensions,
                    submoduleCfg: [],
                    userRemoteConfigs: configs
                ]
    }
    
}


def checkoutSubversionScm(Map checkoutOptions) {

    println "[INFO] SCM Class: ${checkoutOptions['checkoutClass']}"
    println "[INFO] Repository URL: ${checkoutOptions['repositoryUrl']}"

    String credentialsId = checkoutOptions['credentialsId'] ?: ''

    checkout([$class: checkoutOptions['checkoutClass'], additionalCredentials: [], excludedCommitMessages: '', excludedRegions: '', excludedRevprop: '', 
                excludedUsers: '', filterChangelog: false, ignoreDirPropChanges: false, includedRegions: '', 
                locations: [[cancelProcessOnExternalsFail: true, credentialsId: credentialsId, depthOption: 'infinity', 
                ignoreExternalsOption: true, local: '.', remote: checkoutOptions['repositoryUrl']]], 
                quietOperation: true, workspaceUpdater: [$class: 'UpdateUpdater']])
}