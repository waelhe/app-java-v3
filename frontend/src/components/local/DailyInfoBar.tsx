'use client';

import React, { useState, useEffect } from 'react';
import { Moon, Sun, Sunrise, Sunset, CloudMoon, Clock, MapPin, ChevronDown, ChevronUp, Bell, Cloud, Droplets, Wind, Thermometer, Eye } from 'lucide-react';
import { motion, AnimatePresence } from 'framer-motion';
import { useLanguage } from '@/contexts/LanguageContext';
import RegionSelector from './RegionSelector';
import { useRegion, Region } from '@/contexts/RegionContext';

// Prayer Times Data
interface PrayerTime {
  id: string;
  name: string;
  nameEn: string;
  time: string;
  icon: React.ElementType;
  color: string;
  bgColor: string;
}

interface PrayerData {
  prayers: PrayerTime[];
  location: string;
  locationEn: string;
}

const qudsayaCenterPrayers: PrayerData = {
  prayers: [
    { id: 'fajr', name: 'الفجر', nameEn: 'Fajr', time: '05:10', icon: Moon, color: 'text-indigo-600', bgColor: 'bg-indigo-100' },
    { id: 'sunrise', name: 'الشروق', nameEn: 'Sunrise', time: '06:35', icon: Sunrise, color: 'text-orange-500', bgColor: 'bg-orange-100' },
    { id: 'dhuhr', name: 'الظهر', nameEn: 'Dhuhr', time: '12:40', icon: Sun, color: 'text-yellow-500', bgColor: 'bg-yellow-100' },
    { id: 'asr', name: 'العصر', nameEn: 'Asr', time: '16:10', icon: CloudMoon, color: 'text-amber-500', bgColor: 'bg-amber-100' },
    { id: 'maghrib', name: 'المغرب', nameEn: 'Maghrib', time: '18:50', icon: Sunset, color: 'text-rose-500', bgColor: 'bg-rose-100' },
    { id: 'isha', name: 'العشاء', nameEn: 'Isha', time: '20:15', icon: Moon, color: 'text-purple-500', bgColor: 'bg-purple-100' }
  ],
  location: 'قدسيا، دمشق',
  locationEn: 'Qudsaya, Damascus'
};

const qudsayaDahiaPrayers: PrayerData = {
  prayers: [
    { id: 'fajr', name: 'الفجر', nameEn: 'Fajr', time: '05:12', icon: Moon, color: 'text-indigo-600', bgColor: 'bg-indigo-100' },
    { id: 'sunrise', name: 'الشروق', nameEn: 'Sunrise', time: '06:38', icon: Sunrise, color: 'text-orange-500', bgColor: 'bg-orange-100' },
    { id: 'dhuhr', name: 'الظهر', nameEn: 'Dhuhr', time: '12:45', icon: Sun, color: 'text-yellow-500', bgColor: 'bg-yellow-100' },
    { id: 'asr', name: 'العصر', nameEn: 'Asr', time: '16:15', icon: CloudMoon, color: 'text-amber-500', bgColor: 'bg-amber-100' },
    { id: 'maghrib', name: 'المغرب', nameEn: 'Maghrib', time: '18:52', icon: Sunset, color: 'text-rose-500', bgColor: 'bg-rose-100' },
    { id: 'isha', name: 'العشاء', nameEn: 'Isha', time: '20:18', icon: Moon, color: 'text-purple-500', bgColor: 'bg-purple-100' }
  ],
  location: 'ضاحية قدسيا، دمشق',
  locationEn: 'Qudsaya Dahia, Damascus'
};

const prayerDataByRegion: Record<Region, PrayerData> = {
  'qudsaya-center': qudsayaCenterPrayers,
  'qudsaya-dahia': qudsayaDahiaPrayers
};

// Weather Data
interface WeatherData {
  temperature: number;
  feelsLike: number;
  condition: string;
  conditionEn: string;
  humidity: number;
  windSpeed: number;
  visibility: number;
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
  high: 31,
  low: 21,
  location: 'قدسيا، دمشق',
  locationEn: 'Qudsaya, Damascus'
};

const qudsayaDahiaWeather: WeatherData = {
  temperature: 28,
  feelsLike: 30,
  condition: 'مشمس',
  conditionEn: 'Sunny',
  humidity: 45,
  windSpeed: 12,
  visibility: 10,
  high: 32,
  low: 22,
  location: 'ضاحية قدسيا، دمشق',
  locationEn: 'Qudsaya Dahia, Damascus'
};

const weatherDataByRegion: Record<Region, WeatherData> = {
  'qudsaya-center': qudsayaCenterWeather,
  'qudsaya-dahia': qudsayaDahiaWeather
};

export default function DailyInfoBar() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const { region } = useRegion();
  
  const prayerData = prayerDataByRegion[region];
  const weatherData = weatherDataByRegion[region];
  const prayers = prayerData.prayers;
  
  const [prayerInfo, setPrayerInfo] = useState({ id: 'fajr', countdown: '--:--' });
  const [showDetails, setShowDetails] = useState(false);

  useEffect(() => {
    const updatePrayerInfo = () => {
      const now = new Date();
      const currentMinutes = now.getHours() * 60 + now.getMinutes();

      for (const prayer of prayers) {
        const [hours, minutes] = prayer.time.split(':').map(Number);
        const prayerMinutes = hours * 60 + minutes;

        if (prayerMinutes > currentMinutes) {
          const diff = prayerMinutes - currentMinutes;
          const h = Math.floor(diff / 60);
          const m = diff % 60;
          setPrayerInfo({
            id: prayer.id,
            countdown: `${h}:${m.toString().padStart(2, '0')}`
          });
          return;
        }
      }
      setPrayerInfo({ id: 'fajr', countdown: '--:--' });
    };

    updatePrayerInfo();
    const interval = setInterval(updatePrayerInfo, 60000);
    return () => clearInterval(interval);
  }, [prayers]);

  const nextPrayer = prayers.find(p => p.id === prayerInfo.id);
  const NextPrayerIcon = nextPrayer?.icon || Clock;

  return (
    <section className="py-2 md:py-3 lg:py-4 bg-gradient-to-r from-purple-600 via-purple-500 to-sky-500">
      <div className="max-w-7xl mx-auto px-4 md:px-6 lg:px-8">
        <motion.div
          initial={{ opacity: 0, y: -10 }}
          animate={{ opacity: 1, y: 0 }}
          className="bg-white/10 backdrop-blur-md rounded-2xl overflow-hidden border border-white/20"
        >
          {/* Main Bar - Always Visible */}
          <div className="flex items-stretch">
            {/* Prayer Section */}
            <div className="flex-1 p-3 md:p-4 lg:p-5 border-l border-white/20">
              <div className="flex items-center gap-3 md:gap-4">
                <div className="p-2 md:p-3 bg-white/20 rounded-xl md:rounded-2xl">
                  <NextPrayerIcon className="w-5 h-5 md:w-6 md:h-6 lg:w-7 lg:h-7 text-white" />
                </div>
                <div className="flex-1">
                  <p className="text-white/70 text-[10px] md:text-xs font-medium">
                    {isArabic ? 'الصلاة القادمة' : 'Next Prayer'}
                  </p>
                  <div className="flex items-baseline gap-2 md:gap-3">
                    <span className="text-white font-black text-lg md:text-xl lg:text-2xl">
                      {isArabic ? nextPrayer?.name : nextPrayer?.nameEn}
                    </span>
                    <span className="text-white font-bold text-xl md:text-2xl lg:text-3xl">
                      {nextPrayer?.time}
                    </span>
                  </div>
                </div>
                <div className="text-left">
                  <div className="flex items-center gap-1 text-white/80">
                    <Clock className="w-3 h-3 md:w-4 md:h-4" />
                    <span className="text-xs md:text-sm font-medium">
                      {isArabic ? `متبقي ${prayerInfo.countdown}` : `${prayerInfo.countdown} left`}
                    </span>
                  </div>
                </div>
              </div>
            </div>

            {/* Weather Section */}
            <div className="flex-1 p-3 md:p-4 lg:p-5">
              <div className="flex items-center gap-3 md:gap-4">
                <div className="p-2 md:p-3 bg-white/20 rounded-xl md:rounded-2xl">
                  <Sun className="w-5 h-5 md:w-6 md:h-6 lg:w-7 lg:h-7 text-yellow-300" />
                </div>
                <div className="flex-1">
                  <p className="text-white/70 text-[10px] md:text-xs font-medium">
                    {isArabic ? 'الطقس' : 'Weather'}
                  </p>
                  <div className="flex items-baseline gap-2 md:gap-3">
                    <span className="text-white font-black text-xl md:text-2xl lg:text-3xl">
                      {weatherData.temperature}°
                    </span>
                    <span className="text-white/80 text-sm md:text-base lg:text-lg">
                      {isArabic ? weatherData.condition : weatherData.conditionEn}
                    </span>
                  </div>
                </div>
                <div className="text-left hidden sm:block">
                  <div className="flex items-center gap-2 text-white/70 text-xs md:text-sm">
                    <span>{weatherData.high}°/{weatherData.low}°</span>
                    <span>•</span>
                    <span>{weatherData.humidity}%</span>
                  </div>
                </div>
              </div>
            </div>

            {/* Toggle Button */}
            <button
              onClick={() => setShowDetails(!showDetails)}
              className="px-3 md:px-4 flex items-center justify-center bg-white/10 hover:bg-white/20 transition-colors"
            >
              {showDetails 
                ? <ChevronUp className="w-4 h-4 md:w-5 md:h-5 text-white" />
                : <ChevronDown className="w-4 h-4 md:w-5 md:h-5 text-white" />
              }
            </button>
          </div>

          {/* Expanded Details */}
          <AnimatePresence>
            {showDetails && (
              <motion.div
                initial={{ height: 0, opacity: 0 }}
                animate={{ height: 'auto', opacity: 1 }}
                exit={{ height: 0, opacity: 0 }}
                className="overflow-hidden"
              >
                <div className="p-4 bg-white/5 border-t border-white/20">
                  <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                    {/* All Prayer Times */}
                    <div>
                      <h4 className="text-white/70 text-xs font-bold mb-3 flex items-center gap-2">
                        <Bell className="w-3 h-3" />
                        {isArabic ? 'أوقات الصلاة' : 'Prayer Times'}
                      </h4>
                      <div className="grid grid-cols-3 gap-2">
                        {prayers.map((prayer) => {
                          const Icon = prayer.icon;
                          const isNext = prayer.id === prayerInfo.id;
                          return (
                            <div
                              key={prayer.id}
                              className={`p-2 rounded-xl text-center ${
                                isNext ? 'bg-white/30' : 'bg-white/10'
                              }`}
                            >
                              <Icon className={`w-4 h-4 mx-auto mb-1 ${isNext ? 'text-white' : 'text-white/70'}`} />
                              <p className="text-white text-[10px] font-bold">
                                {isArabic ? prayer.name : prayer.nameEn}
                              </p>
                              <p className={`text-sm font-black ${isNext ? 'text-white' : 'text-white/80'}`}>
                                {prayer.time}
                              </p>
                            </div>
                          );
                        })}
                      </div>
                    </div>

                    {/* Weather Details */}
                    <div>
                      <h4 className="text-white/70 text-xs font-bold mb-3 flex items-center gap-2">
                        <Cloud className="w-3 h-3" />
                        {isArabic ? 'تفاصيل الطقس' : 'Weather Details'}
                      </h4>
                      <div className="grid grid-cols-2 gap-2">
                        <div className="p-2 rounded-xl bg-white/10 flex items-center gap-2">
                          <Thermometer className="w-4 h-4 text-white/70" />
                          <div>
                            <p className="text-white/70 text-[10px]">{isArabic ? 'العظمى/الصغرى' : 'High/Low'}</p>
                            <p className="text-white text-sm font-bold">{weatherData.high}°/{weatherData.low}°</p>
                          </div>
                        </div>
                        <div className="p-2 rounded-xl bg-white/10 flex items-center gap-2">
                          <Droplets className="w-4 h-4 text-white/70" />
                          <div>
                            <p className="text-white/70 text-[10px]">{isArabic ? 'الرطوبة' : 'Humidity'}</p>
                            <p className="text-white text-sm font-bold">{weatherData.humidity}%</p>
                          </div>
                        </div>
                        <div className="p-2 rounded-xl bg-white/10 flex items-center gap-2">
                          <Wind className="w-4 h-4 text-white/70" />
                          <div>
                            <p className="text-white/70 text-[10px]">{isArabic ? 'الرياح' : 'Wind'}</p>
                            <p className="text-white text-sm font-bold">{weatherData.windSpeed} km/h</p>
                          </div>
                        </div>
                        <div className="p-2 rounded-xl bg-white/10 flex items-center gap-2">
                          <Eye className="w-4 h-4 text-white/70" />
                          <div>
                            <p className="text-white/70 text-[10px]">{isArabic ? 'الرؤية' : 'Visibility'}</p>
                            <p className="text-white text-sm font-bold">{weatherData.visibility} km</p>
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>

                  {/* Location & Region Selector */}
                  <div className="mt-3 pt-3 border-t border-white/20 flex items-center justify-between">
                    <div className="flex items-center gap-1 text-white/60 text-xs">
                      <MapPin className="w-3 h-3" />
                      <span>{isArabic ? prayerData.location : prayerData.locationEn}</span>
                    </div>
                    <RegionSelector />
                  </div>
                </div>
              </motion.div>
            )}
          </AnimatePresence>
        </motion.div>
      </div>
    </section>
  );
}
