import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('firered_resources',Path(__file__).parents[1]/'prepare_firered_resources.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class FireRedResourcesTest(unittest.TestCase):
    def test_fixed_catalog_copy_manifest_and_refuse_overwrite(self):
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp);model = base/'model';licenses = base/'attribution';model.mkdir();licenses.mkdir()
            data = {'model.int8.onnx':b'model','tokens.txt':b'tokens','licenses/LICENSE':b'license'}
            for name,content in data.items():
                (licenses/name.split('/')[-1] if name.startswith('licenses/') else model/name).write_bytes(content)
            catalog = {'modelId':'fixture','exporterRevision':'a'*40,'files':{name:{'size':len(content),'sha256':hashlib.sha256(content).hexdigest()} for name,content in data.items()}}
            destination = module.prepare(model,licenses,base/'out',catalog)
            self.assertEqual(catalog,json.loads((destination/'manifest.json').read_text()))
            self.assertEqual(b'license',(destination/'licenses/LICENSE').read_bytes())
            with self.assertRaises(ValueError): module.prepare(model,licenses,base/'out',catalog)
            (model/'tokens.txt').write_bytes(b'wrong')
            with self.assertRaises(ValueError): module.prepare(model,licenses,base/'bad',catalog)
            self.assertFalse((base/'bad'/'fixture'/('a'*40)).exists())
            self.assertEqual([],list((base/'bad'/'fixture').glob('.preparing-*')))

if __name__ == '__main__': unittest.main()
