package br.agrosense;

import ai.onnxruntime.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public final class AgroSensePredictor implements AutoCloseable {
    public static final String[] INPUTS = {"type","airTemperature","processTemperature",
        "rotationalSpeed","torque","toolWear","deltaTemp","potenciaW","desgasteXTorque",
        "margemSobrecarga","margemPotencia"};
    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String probabilityOutput;
    private final int positiveIndex;
    private final double threshold;
    private final JsonNode info;

    public AgroSensePredictor(Path directory) throws IOException, OrtException {
        this(directory, false);
    }

    public AgroSensePredictor(Path directory, boolean alternative) throws IOException, OrtException {
        info = new ObjectMapper().readTree(directory.resolve("modelo_info.json").toFile());
        requireObject(info,"Metadata");
        if (!"4.1".equals(text(info,"schema_version"))) throw new IllegalArgumentException("Schema nao suportado");
        if (!"entrada".equals(text(info,"nome_entrada"))) throw new IllegalArgumentException("Nome de entrada mudou");
        JsonNode order=info.path("ordem_das_entradas");
        if(!order.isArray() || order.size()!=11) throw new IllegalArgumentException("Quantidade de entradas mudou");
        for(int i=0;i<11;i++) if(!order.get(i).isTextual() || !INPUTS[i].equals(order.get(i).textValue())) throw new IllegalArgumentException("Ordem das entradas mudou");
        JsonNode classes=info.path("classes");
        if(!classes.isArray() || classes.size()!=2 || !isInteger(classes.get(0),0) || !isInteger(classes.get(1),1))
            throw new IllegalArgumentException("Classes do contrato devem ser [0,1]");
        if(!isInteger(info.path("positive_class"),1) || !isInteger(info.path("positive_index"),1))
            throw new IllegalArgumentException("Classe positiva/indice devem ser inteiros 1");
        positiveIndex=1;
        validateMappings(info);
        JsonNode inputContract=info.path("input");
        requireObject(inputContract,"input");
        if(!"entrada".equals(text(inputContract,"name")) || !"float32".equals(text(inputContract,"dtype")))
            throw new IllegalArgumentException("Contrato de entrada deve ser float32 entrada");
        validateMetadataShape(inputContract.path("shape"),11);
        probabilityOutput=text(info,"saida_probabilidades");
        validateMetadataOutput(info.path("outputs"),probabilityOutput);
        if(alternative) {
            JsonNode op=info.path("alternative_policy");
            requireObject(op,"alternative_policy");
            if(!op.path("feasible").isBoolean() || !op.path("feasible").booleanValue()) throw new IllegalArgumentException("Alternativa nao viavel no desenvolvimento");
            threshold=number(op,"threshold");
        } else threshold=number(info,"threshold");
        if(threshold<0 || threshold>1) throw new IllegalArgumentException("Limiar invalido");
        if(!"agrosense_model.onnx".equals(text(info,"arquivo_modelo"))) throw new IllegalArgumentException("Nome do arquivo ONNX mudou");
        Path model=directory.resolve("agrosense_model.onnx");
        String expectedHash=text(info,"model_sha256");
        byte[] modelBytes=Files.readAllBytes(model);
        if(!expectedHash.matches("[0-9a-f]{64}") || !sha256(modelBytes).equals(expectedHash)) throw new IllegalArgumentException("Hash ONNX diferente do metadata");
        environment=OrtEnvironment.getEnvironment();
        OrtSession opened=null;
        try {
            try(OrtSession.SessionOptions options=new OrtSession.SessionOptions()) {
                options.setIntraOpNumThreads(4); options.setInterOpNumThreads(1);
                opened=environment.createSession(modelBytes,options);
            }
            Map<String,NodeInfo> inputs=opened.getInputInfo();
            if(inputs.size()!=1 || !inputs.containsKey("entrada") || !(inputs.get("entrada").getInfo() instanceof TensorInfo))
                throw new IllegalArgumentException("ONNX deve ter uma entrada tensor chamada entrada");
            validateTensor((TensorInfo)inputs.get("entrada").getInfo(),11,"Entrada");
            NodeInfo out=opened.getOutputInfo().get(probabilityOutput);
            if(out==null || !(out.getInfo() instanceof TensorInfo)) throw new IllegalArgumentException("Saida nomeada ausente");
            validateTensor((TensorInfo)out.getInfo(),2,"Saida de probabilidades");
        } catch(OrtException | RuntimeException | Error failure) {
            if(opened!=null) {
                try { opened.close(); } catch(OrtException closeFailure) { failure.addSuppressed(closeFailure); }
            }
            throw failure;
        }
        session=opened;
    }

    private static void requireObject(JsonNode value,String name) {
        if(value==null || !value.isObject()) throw new IllegalArgumentException(name+" deve ser objeto JSON");
    }

    private static String text(JsonNode row,String key) {
        JsonNode value=row.get(key);
        if(value==null || !value.isTextual() || value.textValue().trim().isEmpty()) throw new IllegalArgumentException("Texto ausente/invalido: "+key);
        return value.textValue();
    }

    private static boolean isInteger(JsonNode value,int expected) {
        return value!=null && value.isIntegralNumber() && value.canConvertToInt() && value.intValue()==expected;
    }

    private static void validateMappings(JsonNode metadata) {
        JsonNode codes=metadata.path("tipo_para_numero"), limits=metadata.path("limite_do_tipo");
        requireObject(codes,"tipo_para_numero"); requireObject(limits,"limite_do_tipo");
        if(codes.size()!=3 || limits.size()!=3 || !isInteger(codes.get("L"),0) || !isInteger(codes.get("M"),1) || !isInteger(codes.get("H"),2))
            throw new IllegalArgumentException("Mapeamento L/M/H mudou");
        if(number(limits,"L")!=11000. || number(limits,"M")!=12000. || number(limits,"H")!=13000.)
            throw new IllegalArgumentException("Limites L/M/H mudaram");
    }

    private static void validateMetadataShape(JsonNode shape,int width) {
        if(!shape.isArray() || shape.size()!=2 || !shape.get(0).isNull() || !isInteger(shape.get(1),width))
            throw new IllegalArgumentException("Shape deve ser [null,"+width+"]");
    }

    private static void validateMetadataOutput(JsonNode outputs,String name) {
        if(!outputs.isArray()) throw new IllegalArgumentException("outputs deve ser array");
        int matches=0;
        for(JsonNode output:outputs) {
            requireObject(output,"output");
            if(name.equals(text(output,"name"))) {
                matches++;
                if(!"tensor(float)".equals(text(output,"dtype"))) throw new IllegalArgumentException("Saida deve ser tensor(float)");
                validateMetadataShape(output.path("shape"),2);
            }
        }
        if(matches!=1) throw new IllegalArgumentException("Saida de probabilidades ausente/duplicada no metadata");
    }

    private static void validateTensor(TensorInfo tensor,int width,String name) {
        long[] shape=tensor.getShape();
        if(tensor.type!=OnnxJavaType.FLOAT || shape.length!=2 || shape[0]!=-1 || shape[1]!=width)
            throw new IllegalArgumentException(name+" ONNX deve ser float[N,"+width+"] com lote dinamico");
    }

    public static double number(JsonNode row,String key) {
        requireObject(row,"Leitura/metadata");
        JsonNode value=row.get(key);
        if(value==null || !value.isNumber() || !Double.isFinite(value.doubleValue())) throw new IllegalArgumentException("Campo numerico finito ausente/invalido: "+key);
        return value.doubleValue();
    }

    public static float[] features(JsonNode row) {
        requireObject(row,"Leitura");
        JsonNode typeNode=row.get("type");
        if(typeNode==null || !typeNode.isTextual()) throw new IllegalArgumentException("Type ausente");
        String type=typeNode.textValue(); int code; double limit;
        switch(type) {
            case "L": code=0; limit=11000.; break;
            case "M": code=1; limit=12000.; break;
            case "H": code=2; limit=13000.; break;
            default: throw new IllegalArgumentException("Type deve ser L/M/H");
        }
        double air=number(row,"airTemperature"), process=number(row,"processTemperature"), rpm=number(row,"rotationalSpeed");
        double torque=number(row,"torque"), wear=number(row,"toolWear");
        if(air<=0 || process<=0 || rpm<=0 || torque<=0 || wear<0) throw new IllegalArgumentException("Unidades/valores invalidos: K, rpm, Nm >0; min >=0");
        double delta=process-air;
        double power=torque*rpm*2*Math.PI/60;
        double wearTorque=wear*torque;
        double[] all={code,air,process,rpm,torque,wear,delta,power,wearTorque,wearTorque-limit,Math.min(power-3500,9000-power)};
        float[] result=new float[11];
        for(int i=0;i<11;i++) {
            result[i]=(float)all[i];
            if(!Float.isFinite(result[i])) throw new IllegalArgumentException("Overflow float32");
        }
        return result;
    }

    public double getThreshold() { return threshold; }
    public boolean decision(double score) {
        if(!Double.isFinite(score) || score<0 || score>1) throw new IllegalArgumentException("Score invalido");
        return score>=threshold;
    }

    public Prediction[] predict(List<JsonNode> readings) throws OrtException {
        if(readings==null || readings.isEmpty()) throw new IllegalArgumentException("Lote ausente/vazio");
        float[][] batch=new float[readings.size()][11];
        for(int i=0;i<batch.length;i++) batch[i]=features(readings.get(i));
        try(OnnxTensor input=OnnxTensor.createTensor(environment,batch);
            OrtSession.Result output=session.run(Collections.singletonMap("entrada",input),Collections.singleton(probabilityOutput))) {
            OnnxValue value=output.get(probabilityOutput).orElseThrow(()->new IllegalStateException("Saida nao retornada"));
            float[][] probability=validateProbabilities(value.getValue(),batch.length);
            Prediction[] predictions=new Prediction[batch.length];
            for(int i=0;i<batch.length;i++) {
                double score=(double)probability[i][positiveIndex];
                predictions[i]=new Prediction(score,threshold,decision(score));
            }
            return predictions;
        }
    }

    static float[][] validateProbabilities(Object value,int expectedRows) {
        if(expectedRows<1 || !(value instanceof float[][])) throw new IllegalStateException("Saida ONNX deve ser float[N,2]");
        float[][] probability=(float[][])value;
        if(probability.length!=expectedRows) throw new IllegalStateException("Quantidade de respostas ONNX difere do lote");
        for(float[] row:probability) {
            if(row==null || row.length!=2) throw new IllegalStateException("Resposta ONNX deve ter duas classes");
            for(float score:row) if(!Float.isFinite(score) || score<0 || score>1)
                throw new IllegalStateException("Probabilidade ONNX invalida");
        }
        return probability;
    }

    public Prediction predict(JsonNode reading) throws OrtException {
        return predict(Collections.singletonList(reading))[0];
    }

    public static final class Prediction {
        public final double scoreFalha;
        public final double limiar;
        public final boolean falha;
        public final String alerta;
        public Prediction(double score,double threshold,boolean decision) {
            scoreFalha=score; limiar=threshold; falha=decision;
            alerta=decision ? "alerta_falha" : "sem_alerta_por_este_modelo";
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            StringBuilder text=new StringBuilder();
            for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes)) text.append(String.format("%02x",b&255));
            return text.toString();
        } catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    @Override public void close() throws OrtException { session.close(); }
}
