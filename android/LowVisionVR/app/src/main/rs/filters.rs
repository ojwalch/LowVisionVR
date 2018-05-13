#pragma version(1)
#pragma rs java_package_name(com.lowvisionvr.ojwalch.lowvisionvr)
#pragma rs_fp_relaxed
#include "rs_debug.rsh"

static rs_allocation raw;

uint32_t gW;
uint32_t gH;
uint32_t param;

void set_input(rs_allocation u8_buf) {
	raw = u8_buf;
}

void set_height(uint32_t height) {
	gH = height;
}

void set_width(uint32_t width) {
	gW = width;
}

void set_param(uint32_t p) {
	param = p;
}

inline static float getElementAt_uchar_to_float(rs_allocation a, uint32_t x,
		uint32_t y) {
	return rsGetElementAt_uchar(a, x, y) / 255.0f;
}



uchar4 __attribute__((kernel)) getValueAtPoint(uint32_t x, uint32_t y){

    y = max(y, (uint32_t)0);
    y = min(y, (uint32_t)gH-1);
    x = min(x, (uint32_t)gW-1);
    x = max(x, (uint32_t)0);

    uchar yps = rsGetElementAt_uchar(raw, x, y);
    uchar u = rsGetElementAt_uchar(raw,(x & ~1),gH + (y>>1));
    uchar v = rsGetElementAt_uchar(raw,(x & ~1)+1,gH + (y>>1));
    uchar4 rgb = rsYuvToRGBA_uchar4(yps, v, u);
    return rgb;
}

uchar4 __attribute__((kernel)) getBlurredValueAtPoint(uint32_t x, uint32_t y) {
	uchar4 v_out = (uchar4){0, 0, 0, 0};

    const float k1 = 0.1715728f; // w = 2
    const float k2 = 0.0857864f; // w = 1
    const float k3 = 0.0606601f; // w = 1/1.4 = 0.7

    uint32_t n = max(y - 1, (uint32_t)0);
    uint32_t s = min(y + 1, (uint32_t)gH);
    uint32_t e = min(x + 1, (uint32_t)gW);
    uint32_t w = max(x - 1, (uint32_t)0);

    uchar4 e11 = getValueAtPoint(w,n);
    uchar4 e21 = getValueAtPoint(x,n);
    uchar4 e31 = getValueAtPoint(e,n);
    uchar4 e12 = getValueAtPoint(w,y);
    uchar4 e22 = getValueAtPoint(x,y);
    uchar4 e32 = getValueAtPoint(e,y);
    uchar4 e13 = getValueAtPoint(w,s);
    uchar4 e23 = getValueAtPoint(x,s);
    uchar4 e33 = getValueAtPoint(e,s);

    for(int i = 0; i < 3; i++){

        v_out[i] = (uchar)( e22[i] * k1 +
                    (e21[i] + e12[i]  + e32[i]  + e23[i]) * k2 +
                    (e11[i] + e31[i]  + e13[i] + e33[i]) * k3);

    }

    v_out[3] = 255;
	return v_out;
}


 void edgeProcessing(uchar4* v_out, uint32_t x, uint32_t y){

    uint32_t n = max(y - 1, (uint32_t)0);
    uint32_t s = min(y + 1, (uint32_t)gH-1);
    uint32_t e = min(x + 1, (uint32_t)gW-1);
    uint32_t w = max(x - 1, (uint32_t)0);

    uchar4 e11 = getValueAtPoint(w,n);
    uchar4 e12 = getValueAtPoint(w,y);
    uchar4 e21 = getValueAtPoint(x,n);
    uchar4 e31 = getValueAtPoint(e,n);
    uchar4 e13 = getValueAtPoint(w,s);
    uchar4 e23 = getValueAtPoint(x,s);
    uchar4 e32 = getValueAtPoint(e,y);
    uchar4 e33 = getValueAtPoint(e,s);

    // Average across colors
    float f11 = ((float)e11[0] + (float)e11[1] + (float)e11[2])/3.0;
    float f21 = ((float)e21[0] + (float)e21[1] + (float)e21[2])/3.0;
    float f12 = ((float)e12[0] + (float)e12[1] + (float)e12[2])/3.0;

    float f31 = ((float)e31[0] + (float)e31[1] + (float)e31[2])/3.0;
    float f13 = ((float)e13[0] + (float)e13[1] + (float)e13[2])/3.0;
    float f23 = ((float)e23[0] + (float)e23[1] + (float)e23[2])/3.0;
    float f32 = ((float)e32[0] + (float)e32[1] + (float)e32[2])/3.0;

    float f33 = ((float)e33[0] + (float)e33[1] + (float)e33[2])/3.0;


    float tmp_h = (f21 - f23)*2 + f11 - f13 + f31 - f33;
    float tmp_v = (f12 - f32)*2 + f11 - f31 + f13 - f33;

    float tmp = sqrt(tmp_h*tmp_h + tmp_v*tmp_v);

    if(param == 1){
        *v_out = (uchar4){tmp,tmp,tmp, 255};
    }
    if(param == 2){
        *v_out = (uchar4){255 - tmp,255 - tmp,255 - tmp, 255};
    }

    if(param != 1 && param != 2){
        uchar4 point = getValueAtPoint(x,y);
        *v_out = (uchar4){(255 - tmp)/255*point[0],(255 - tmp)/255*point[1],(255 - tmp)/255*point[2], 255};
    }


}


void centerProcessing(uchar4* v_out, uint32_t x, uint32_t y){

    int W = gW;
    int H = gH;
    int R1 = min(W,H)/8;
    int R2 = min(W,H)/3;

    if(param == 1){
        R1 = min(W,H)/6;
        R2 = min(W,H)/2.5;
    }else if (param == 2){
        R1 = min(W,H)/4;
        R2 = min(W,H)/2.5;
    }

    float mult = 1;
    uchar4 newOutput;
    float r = sqrt((float)(((int) y - H/2)*((int) y - H/2) + ((int) x - W/2)*((int) x - W/2)));

    if(r*r < R2*R2 && r*r > R1*R1){

        int mp = (int) y;
        int np = (int) x;

        float th = atan2((float)(mp - H/2),(float)(np - W/2));
        np = R2*(r - R1)*cos(th)/(R2 - R1) +  W/2;
        mp = R2*(r - R1)*sin(th)/(R2 - R1) +  H/2;
        mp = round((float)mp);
        np = round((float)np);

        newOutput = getValueAtPoint(np,mp);

    }else if(r*r <= R1*R1){
        mult = 0;
        newOutput = getValueAtPoint(x,y);

    }else{
        mult = 1;
        newOutput = getValueAtPoint(x,y);
    }

    *v_out = (uchar4){mult*newOutput[0], mult*newOutput[1], mult*newOutput[2], 255};

}

void warpProcessing(uchar4* v_out, uint32_t x, uint32_t y){

    int L = gW;
    int R = (int)((float)L/3.0f);

    if(param == 1){
        R = (int)((float)L/4.0f);
    }else if (param == 2){
        R = (int)((float)L/2.5f);
    }

    float mult = 1;
    uchar4 newOutput;
    int mp = (int) y;
    int np = (int) x;

    if(np < R && np >= 0){
        np = round((asin((float)(np)/R) + M_PI_2)*(float)L/M_PI);
        mp = round((float)mp);
        np = (int)round((float)np - L/2) % L;

        newOutput = getValueAtPoint(np,mp);

    }else if(np >= L - R && np < L){
        np = round((asin((float)(np - L)/R) + M_PI_2)*(float)L/M_PI);
        mp = round((float)mp);
        np = (int)round((float)np - L/2 + L) % L;


        newOutput = getValueAtPoint(np,mp);

    }else{
        mult = 0;
        newOutput = getValueAtPoint(x,y);
    }

    *v_out = (uchar4){mult*newOutput[0], mult*newOutput[1], mult*newOutput[2], 255};
}


void peripheryProcessing(uchar4* v_out, uint32_t x, uint32_t y){

    int L = gW;

    int R = (int)((float)L/3.0f);

    if(param == 1){
        R = (int)((float)L/4.0f);
    }else if (param == 2){
        R = (int)((float)L/2.5f);
    }

    float mult = 1;
    uchar4 newOutput;
    int mp = (int) y;
    int np = (int) x;

    if(np >= (L/2 - R) && np < (L/2 + R)){
        np = round((asin((float)(np - L/2)/R) + M_PI_2)*(float)L/M_PI);
        mp = round((float)mp);
        np = round((float)np);
        newOutput = getValueAtPoint(np,mp);
    }else{
        mult = 0;
        newOutput = getValueAtPoint(x,y);
    }

   *v_out = (uchar4){mult*newOutput[0], mult*newOutput[1], mult*newOutput[2], 255};
}

uchar4 __attribute__((kernel)) edgeKernel(uint32_t x, uint32_t y){

    uchar4 rgb;
    edgeProcessing(&rgb,x,y);
    return rgb;

}


uchar4 __attribute__((kernel)) centerKernel(uint32_t x, uint32_t y){

    uchar4 rgb;
    centerProcessing(&rgb,x,y);
    return rgb;

}


uchar4 __attribute__((kernel)) warpKernel(uint32_t x, uint32_t y){

    uchar4 rgb;
    warpProcessing(&rgb,x,y);
    return rgb;

}



uchar4 __attribute__((kernel)) peripheryKernel(uint32_t x, uint32_t y){
    uchar4 rgb;
    peripheryProcessing(&rgb,x,y);
    return rgb;

}
