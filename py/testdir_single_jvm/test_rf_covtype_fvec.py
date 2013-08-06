import unittest, random, sys, time
sys.path.extend(['.','..','py'])
import h2o, h2o_cmd, h2o_rf as h2o_rf, h2o_hosts, h2o_import as h2i, h2o_exec

# we can pass ntree thru kwargs if we don't use the "trees" parameter in runRF
# only classes 1-7 in the 55th col
# rng DETERMINISTIC is default
paramDict = {
    # FIX! if there's a header, can you specify column number or column header
    'response_variable': 54,
    'class_weight': None,
    'ntree': 10,
    'model_key': 'model_keyA',
    'out_of_bag_error_estimate': 1,
    'stat_type': 'ENTROPY',
    'depth': 2147483647, 
    'bin_limit': 10000,
    'parallel': 1,
    'ignore': "1,2,6,7,8",
    'sample': 66,
    ## 'seed': 3,
    ## 'features': 30,
    'exclusive_split_limit': 0,
    }

class Basic(unittest.TestCase):
    def tearDown(self):
        h2o.check_sandbox_for_errors()

    @classmethod
    def setUpClass(cls):
        global localhost
        localhost = h2o.decide_if_localhost()
        if (localhost):
            h2o.build_cloud(node_count=1, java_heap_GB=10)
        else:
            h2o_hosts.build_cloud_with_hosts(node_count=1, java_heap_GB=10)
        h2o.beta_features = True # fvec

    @classmethod
    def tearDownClass(cls):
        h2o.tear_down_cloud()

    def test_rf_covtype_fvec(self):
        importFolderPath = "/home/0xdiag/datasets/standard"
        csvFilename = 'covtype.data'
        csvPathname = importFolderPath + "/" + csvFilename
        key2 = csvFilename + ".hex"
        h2i.setupImportFolder(None, importFolderPath)

        print "\nUsing header=0 on the normal covtype.data"
        parseKey = h2i.parseImportFolderFile(None, csvFilename, importFolderPath, key2=key2,
            header=0, timeoutSecs=180)

        inspect = h2o_cmd.runInspect(None, parseKey['destination_key'])

        for trial in range(1):
            # adjust timeoutSecs with the number of trees
            # seems ec2 can be really slow
            kwargs = paramDict.copy()
            timeoutSecs = 30 + kwargs['ntree'] * 20
            start = time.time()
            # do oobe
            kwargs['out_of_bag_error_estimate'] = 1
            kwargs['model_key'] = "model_" + str(trial)
            
            # don't poll for fvec 
            rfv = h2o_cmd.runRFOnly(parseKey=parseKey, timeoutSecs=timeoutSecs, noPoll=True, **kwargs)
            elapsed = time.time() - start
            print "RF dispatch end on ", csvPathname, 'took', elapsed, 'seconds.', \
                "%d pct. of timeout" % ((elapsed/timeoutSecs) * 100)

            h2o_jobs.pollWaitJobs(pattern='RF_model', timeoutSecs=180, pollTimeoutSecs=120, retryDelaySecs=5)

            print h2o.dump_json(rfv)

if __name__ == '__main__':
    h2o.unit_main()