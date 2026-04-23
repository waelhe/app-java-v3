'use client';

import React from 'react';
import { Sun, Cloud, CloudRain, Droplets, Wind, Thermometer, MapPin, Eye, Gauge } from 'lucide-react';
import { motion } from 'framer-motion';
import { useLanguage } from '@/contexts/LanguageContext';
import RegionSelector from './RegionSelector';
import { useRegion, Region } from '@/contexts/RegionContext';

interface WeatherData {
  temperature: number;
  feelsLike: number;
  condition: string;
  conditionEn: string;
  humidity: number;
  windSpeed: number;
  visibility: number;
  pressure: number;
  high: number;
  low: number;
  location: string;
  locationEn: string;
}

const qudsayaCenterWeather: WeatherData = {
  temperature: 27,
  feelsLike: 29,
  condition: 'مشمس',
  conditionEn: 'Sunny',
  humidity: 42,
  windSpeed: 10,
  visibility: 12,
  pressure: 1018,
  high: 31,
  low: 21,
  location: 'قدسيا، دمشق، سوريا',
  locationEn: 'Qudsaya, Damascus, Syria'
};

const qudsayaDahiaWeather: WeatherData = {
  temperature: 28,
  feelsLike: 30,
  condition: 'مشمس',
  conditionEn: 'Sunny',
  humidity: 45,
  windSpeed: 12,
  visibility: 10,
  pressure: 1015,
  high: 32,
  low: 22,
  location: 'ضاحية قدسيا، دمشق، سوريا',
  locationEn: 'Qudsaya Dahia, Damascus, Syria'
};

const dataByRegion: Record<Region, WeatherData> = {
  'qudsaya-center': qudsayaCenterWeather,
  'qudsaya-dahia': qudsayaDahiaWeather
};

export default function WeatherWidget() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const { region, regionName } = useRegion();
  const weatherData = dataByRegion[region];

  return (
    <section className="py-3 bg-gradient-to-b from-sky-50 to-white">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <motion.div
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          className="bg-gradient-to-br from-sky-500 via-blue-500 to-indigo-600 rounded-2xl p-4 text-white shadow-xl shadow-blue-200/50"
        >
          <div className="flex flex-col md:flex-row items-center gap-4">
            {/* Main Weather Info */}
            <div className="flex items-center gap-4 flex-1">
              <div className="p-3 bg-white/20 rounded-2xl backdrop-blur-sm border border-white/30">
                <Sun className="w-10 h-10 text-yellow-300" />
              </div>
              <div>
                <div className="flex items-baseline gap-1">
                  <span className="text-4xl font-black">{weatherData.temperature}</span>
                  <span className="text-lg font-bold">°C</span>
                </div>
                <p className="text-sky-100 text-sm font-medium">
                  {isArabic ? weatherData.condition : weatherData.conditionEn}
                </p>
                <p className="text-sky-200 text-xs">
                  {isArabic 
                    ? `تشعر كـ ${weatherData.feelsLike}°`
                    : `Feels like ${weatherData.feelsLike}°`}
                </p>
              </div>
            </div>

            {/* Weather Details */}
            <div className="grid grid-cols-4 gap-2 flex-1">
              <div className="bg-white/10 rounded-xl p-2 backdrop-blur-sm border border-white/20 text-center">
                <div className="flex items-center justify-center gap-1 mb-1">
                  <Thermometer className="w-3 h-3 text-sky-200" />
                  <span className="text-[10px] text-sky-200 font-medium">
                    {isArabic ? 'ع/ص' : 'H/L'}
                  </span>
                </div>
                <p className="text-sm font-bold">
                  {weatherData.high}°/{weatherData.low}°
                </p>
              </div>

              <div className="bg-white/10 rounded-xl p-2 backdrop-blur-sm border border-white/20 text-center">
                <div className="flex items-center justify-center gap-1 mb-1">
                  <Droplets className="w-3 h-3 text-sky-200" />
                  <span className="text-[10px] text-sky-200 font-medium">
                    {isArabic ? 'رطوبة' : 'Hum'}
                  </span>
                </div>
                <p className="text-sm font-bold">{weatherData.humidity}%</p>
              </div>

              <div className="bg-white/10 rounded-xl p-2 backdrop-blur-sm border border-white/20 text-center">
                <div className="flex items-center justify-center gap-1 mb-1">
                  <Wind className="w-3 h-3 text-sky-200" />
                  <span className="text-[10px] text-sky-200 font-medium">
                    {isArabic ? 'رياح' : 'Wind'}
                  </span>
                </div>
                <p className="text-sm font-bold">{weatherData.windSpeed}</p>
              </div>

              <div className="bg-white/10 rounded-xl p-2 backdrop-blur-sm border border-white/20 text-center">
                <div className="flex items-center justify-center gap-1 mb-1">
                  <Eye className="w-3 h-3 text-sky-200" />
                  <span className="text-[10px] text-sky-200 font-medium">
                    {isArabic ? 'رؤية' : 'Vis'}
                  </span>
                </div>
                <p className="text-sm font-bold">{weatherData.visibility}km</p>
              </div>
            </div>
          </div>

          {/* Location */}
          <div className="mt-3 pt-3 border-t border-white/20 flex items-center justify-between">
            <div className="flex items-center gap-1 text-sky-100">
              <MapPin className="w-3 h-3" />
              <span className="text-xs font-medium">
                {isArabic ? weatherData.location : weatherData.locationEn}
              </span>
            </div>
            <div className="flex items-center gap-2">
              <span className="text-sky-200 text-[10px]">
                {isArabic ? 'آخر تحديث: الآن' : 'Updated: now'}
              </span>
              <RegionSelector />
            </div>
          </div>
        </motion.div>
      </div>
    </section>
  );
}
